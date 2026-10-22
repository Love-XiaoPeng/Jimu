package io.github.lovexiaopeng.jimu.adapters.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.Cancellation;
import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native POSIX workspace. A task is one atomic, fsynced document under a pinned directory handle.
 * Logical model paths never become OS paths. A durable intent fixes the observation before write;
 * the content and operation receipt then commit together. Locks cover processes as well as threads.
 */
public final class SecureWorkspaceStore {
  public static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final int MAX_STATE_BYTES = 128 * 1024 * 1024;
  private static final ObjectMapper STATE_JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxStringLength(MAX_STATE_BYTES)
                          .maxDocumentLength(MAX_STATE_BYTES)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Object[] LOCKS = new Object[64];

  static {
    for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
  }

  public record FileValue(
      long version,
      String content,
      String sha256,
      long size,
      @JsonInclude(JsonInclude.Include.NON_NULL) Long modifiedAt) {
    public FileValue(long version, String content, String sha256, long size) {
      this(version, content, sha256, size, null);
    }
  }

  public record WorkspaceSnapshot(String digest, Map<String, FileValue> files) {
    public WorkspaceSnapshot {
      files = Map.copyOf(files);
    }
  }

  public record Operation(String kind, String inputDigest, long expected, FileValue result) {}

  public record State(
      int format,
      String scope,
      Map<String, FileValue> files,
      Map<String, Long> observed,
      Map<String, Operation> operations,
      Map<String, Map<String, Long>> bindings) {}

  private record Envelope(String digest, String payload) {}

  @FunctionalInterface
  interface Boundary {
    void hit(String name);
  }

  private final Path root;
  private final Boundary boundary;
  private final Clock clock;

  /** The operator must provision a private POSIX root; no model-controlled mkdir occurs here. */
  public SecureWorkspaceStore(Path root) throws IOException {
    this(root, name -> {});
  }

  SecureWorkspaceStore(Path root, Boundary boundary) throws IOException {
    this(root, boundary, Clock.systemUTC());
  }

  SecureWorkspaceStore(Path root, Boundary boundary, Clock clock) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    this.boundary = boundary;
    this.clock = clock;
    try (var directory = openRoot()) {
      var permissions =
          directory
              .getFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)
              .readAttributes()
              .permissions();
      if (!permissions.equals(PosixFilePermissions.fromString("rwx------")))
        throw new IOException("Workspace requires a private POSIX root");
      forceDirectory(directory);
    }
  }

  /** Pins are fixed at task creation; initialization precedes every workspace operation. */
  public void initialize(String tenant, String task, boolean format3, Cancellation cancellation) {
    String scope = scope(tenant, task);
    locked(
        scope,
        cancellation,
        directory -> {
          State state;
          try (var existing =
              directory.newByteChannel(
                  Path.of(scope + ".json"),
                  Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            state = load(directory, scope);
            if (state.format() != (format3 ? 3 : 2)) throw new FileFailure("FS_BINDING_UNPROVEN");
          } catch (NoSuchFileException absent) {
            state =
                new State(
                    format3 ? 3 : 2,
                    scope,
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>(),
                    new HashMap<>());
            save(directory, state);
          }
          return null;
        });
  }

  /** A search observes one state without granting edit/read observation authority. */
  public WorkspaceSnapshot searchSnapshot(String tenant, String task, Cancellation cancellation) {
    String scope = scope(tenant, task);
    return locked(
        scope,
        cancellation,
        directory -> {
          State state = load(directory, scope);
          if (state.format() != 3) throw new FileFailure("FS_BINDING_UNPROVEN");
          var files = new java.util.TreeMap<>(state.files());
          return new WorkspaceSnapshot(Payloads.hash(Payloads.json(files)), files);
        });
  }

  private static String scope(String tenant, String task) {
    if (!tenant.matches("[A-Za-z0-9_-]{1,64}") || !UUID.fromString(task).toString().equals(task))
      throw new IllegalArgumentException("Invalid workspace scope");
    return Payloads.hash(tenant + ":" + task);
  }

  /** Host preflight, under the same native boundary, without advancing model observations. */
  public FilePort.Snapshot inspect(
      String tenant, String task, String path, Cancellation cancellation) {
    if (!tenant.matches("[A-Za-z0-9_-]{1,64}") || !UUID.fromString(task).toString().equals(task))
      throw new IllegalArgumentException("Invalid workspace scope");
    String name = logicalPath(path), scope = Payloads.hash(tenant + ":" + task);
    return locked(
        scope,
        cancellation,
        directory -> {
          var value = load(directory, scope).files().get(name);
          return value == null
              ? new FilePort.Snapshot(name, 0, null, 0, "")
              : new FilePort.Snapshot(
                  name, value.version(), value.sha256(), value.size(), value.content());
        });
  }

  /** New admission only. The host must use resume for a previously admitted operation. */
  public FilePort bind(String tenant, String task, String operationId, Cancellation cancellation) {
    return bind(tenant, task, operationId, cancellation, false);
  }

  /** Recovery never creates a binding or adopts current observations. */
  public FilePort resume(
      String tenant, String task, String operationId, Cancellation cancellation) {
    return bind(tenant, task, operationId, cancellation, true);
  }

  private FilePort bind(
      String tenant, String task, String operationId, Cancellation cancellation, boolean recovery) {
    if (!tenant.matches("[A-Za-z0-9_-]{1,64}")
        || !UUID.fromString(task).toString().equals(task)
        || !UUID.fromString(operationId).toString().equals(operationId))
      throw new IllegalArgumentException("Invalid workspace scope");
    String scope = Payloads.hash(tenant + ":" + task);
    // Persist before returning a port. Intent rename may never happen; recovery must not
    // reconstruct this snapshot from observations subsequently advanced by another operation.
    Map<String, Long> boundObservations =
        locked(
            scope,
            cancellation,
            directory -> {
              State state = load(directory, scope);
              var original = state.bindings().get(operationId);
              if (recovery) {
                // Without the invocation receipt its original kind/arguments are unproven too.
                if (original == null || !state.operations().containsKey(operationId))
                  throw new FileFailure("FS_BINDING_UNPROVEN");
                return Map.copyOf(original);
              }
              if (original != null) throw new FileFailure("FS_OPERATION_MISMATCH");
              if (state.bindings().size() >= 100) throw new FileFailure("WORKSPACE_LIMIT");
              var frozen = Map.copyOf(state.observed());
              state.bindings().put(operationId, frozen);
              save(directory, state, "BINDING_BEFORE_RENAME");
              boundary.hit("BINDING_DURABLE");
              return frozen;
            });
    class BoundPort implements FilePort, EditPort {
      @Override
      public Read read(String path, int offset, int limit) {
        String name = logicalPath(path);
        if (offset < 1 || limit < 1 || limit > 2000) throw new FileFailure("INPUT_INVALID");
        return locked(
            scope,
            cancellation,
            directory -> {
              State state = load(directory, scope);
              String input = Payloads.hash(Payloads.json(java.util.List.of(name, offset, limit)));
              Operation old = operation(state, operationId, "READ", input);
              FileValue value;
              if (old != null) value = old.result();
              else {
                value = state.files().get(name);
                state.observed().put(name, value == null ? 0 : value.version());
                state.operations().put(operationId, new Operation("READ", input, 0, value));
                save(directory, state);
              }
              if (value == null) return new Read(name, false, 0, null, 0, offset, 0, "");
              String[] lines = value.content().split("\n", -1);
              StringBuilder selected = new StringBuilder();
              for (int line = offset - 1;
                  line < lines.length && (long) line < (long) offset - 1 + limit;
                  line++) selected.append(line + 1).append(": ").append(lines[line]).append('\n');
              return new Read(
                  name,
                  true,
                  value.version(),
                  value.sha256(),
                  value.size(),
                  offset,
                  lines.length,
                  selected.toString());
            });
      }

      @Override
      public Written write(String path, String content) {
        String name = logicalPath(path);
        long size = utf8Size(content);
        if (size > MAX_BYTES) throw new FileFailure("WORKSPACE_LIMIT");
        return locked(
            scope,
            cancellation,
            directory -> {
              State state = load(directory, scope);
              String input = Payloads.hash(Payloads.json(java.util.List.of(name, content)));
              Operation intent = operation(state, operationId, "WRITE", input);
              if (intent != null && intent.result() != null) return written(name, intent.result());
              if (intent == null) {
                Long expected = boundObservations.get(name);
                if (expected == null) throw new FileFailure("FS_NOT_OBSERVED");
                intent = new Operation("WRITE", input, expected, null);
                state.operations().put(operationId, intent);
                save(directory, state);
                boundary.hit("WRITE_INTENT_DURABLE");
              }
              FileValue current = state.files().get(name);
              long version = current == null ? 0 : current.version();
              if (version != intent.expected()) throw new FileFailure("FS_STALE_VERSION");
              long total = state.files().values().stream().mapToLong(FileValue::size).sum();
              if (total - (current == null ? 0 : current.size()) + size > MAX_BYTES)
                throw new FileFailure("WORKSPACE_LIMIT");
              FileValue result = value(state, version + 1, content, size);
              state.files().put(name, result);
              state.observed().put(name, result.version());
              state
                  .operations()
                  .put(operationId, new Operation("WRITE", input, intent.expected(), result));
              cancellation.throwIfCancelled();
              save(directory, state);
              boundary.hit("WRITE_COMMITTED");
              return written(name, result);
            });
      }

      @Override
      public Written edit(String path, String oldString, String newString, boolean replaceAll) {
        String name = logicalPath(path);
        if (utf8Size(oldString) == 0
            || utf8Size(oldString) > 16384
            || utf8Size(newString) > 16384
            || oldString.equals(newString)) throw new FileFailure("INPUT_INVALID");
        String input =
            Payloads.hash(
                Payloads.json(List.of("literal-v1", name, oldString, newString, replaceAll)));
        return locked(
            scope,
            cancellation,
            directory -> {
              State state = load(directory, scope);
              if (state.format() != 3) throw new FileFailure("FS_BINDING_UNPROVEN");
              Operation intent = operation(state, operationId, "EDIT", input);
              if (intent != null && intent.result() != null) return written(name, intent.result());
              if (intent == null) {
                Long expected = boundObservations.get(name);
                if (expected == null) throw new FileFailure("FS_NOT_OBSERVED");
                if (expected == 0) throw new FileFailure("FS_NOT_FOUND");
                intent = new Operation("EDIT", input, expected, null);
                state.operations().put(operationId, intent);
                save(directory, state);
                boundary.hit("EDIT_INTENT_DURABLE");
              }
              FileValue current = state.files().get(name);
              if (current == null || current.version() != intent.expected())
                throw new FileFailure("FS_STALE_VERSION");
              if (current.content().indexOf('\0') >= 0) throw new FileFailure("FS_NOT_TEXT");
              String content = replaceLiteral(current.content(), oldString, newString, replaceAll);
              long size = utf8Size(content);
              long total = state.files().values().stream().mapToLong(FileValue::size).sum();
              if (size > MAX_BYTES || total - current.size() + size > MAX_BYTES)
                throw new FileFailure("WORKSPACE_LIMIT");
              FileValue result = value(state, current.version() + 1, content, size);
              state.files().put(name, result);
              state.observed().put(name, result.version());
              state
                  .operations()
                  .put(operationId, new Operation("EDIT", input, intent.expected(), result));
              cancellation.throwIfCancelled();
              save(directory, state);
              boundary.hit("EDIT_COMMITTED");
              return written(name, result);
            });
      }

      @Override
      public Snapshot snapshot(String path) {
        String name = logicalPath(path);
        return locked(
            scope,
            cancellation,
            directory -> {
              State state = load(directory, scope);
              String input = Payloads.hash(name);
              String presentInput = Payloads.hash("present");
              if (operation(state, operationId, "PRESENT", presentInput) == null)
                state
                    .operations()
                    .put(operationId, new Operation("PRESENT", presentInput, 0, null));
              // A present call may contain several files; each gets its own immutable subreceipt.
              String receipt = operationId + ":" + input;
              Operation old = operation(state, receipt, "PRESENT", input);
              FileValue value;
              if (old != null) value = old.result();
              else {
                Long observed = boundObservations.get(name);
                if (observed == null) throw new FileFailure("FS_NOT_OBSERVED");
                value = state.files().get(name);
                if (value == null) throw new FileFailure("FS_NOT_FOUND");
                if (value.version() != observed) throw new FileFailure("FS_STALE_VERSION");
                state.operations().put(receipt, new Operation("PRESENT", input, observed, value));
                save(directory, state);
              }
              return new Snapshot(
                  name, value.version(), value.sha256(), value.size(), value.content());
            });
      }
    }
    return new BoundPort();
  }

  private FileValue value(State state, long version, String content, long size) {
    Long modified = null;
    if (state.format() == 3) {
      long previous =
          state.files().values().stream().mapToLong(v -> v.modifiedAt()).max().orElse(0);
      var now = clock.instant();
      modified =
          Math.max(
              Math.addExact(previous, 1),
              Math.addExact(
                  Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano()));
    }
    return new FileValue(version, content, Payloads.hash(content), size, modified);
  }

  private static String replaceLiteral(
      String raw, String oldString, String newString, boolean all) {
    String sample = raw.substring(0, Math.min(4096, raw.length()));
    int crlf = 0, lf = 0;
    for (int i = 0; i < sample.length(); i++)
      if (sample.charAt(i) == '\n') {
        if (i > 0 && sample.charAt(i - 1) == '\r') crlf++;
        else lf++;
      }
    String text = (raw.startsWith("\uFEFF") ? raw.substring(1) : raw).replace("\r\n", "\n");
    String old = oldString.replace("\r\n", "\n"), replacement = newString.replace("\r\n", "\n");
    int first = text.indexOf(old);
    if (first < 0) throw new FileFailure("FS_EDIT_NOT_FOUND");
    if (!all && text.indexOf(old, first + old.length()) >= 0)
      throw new FileFailure("FS_AMBIGUOUS_EDIT");
    // String.replace is literal and advances non-overlapping matches; no regex replacement rules.
    String result =
        all
            ? text.replace(old, replacement)
            : text.substring(0, first) + replacement + text.substring(first + old.length());
    return crlf > lf ? result.replace("\n", "\r\n") : result;
  }

  private static FilePort.Written written(String name, FileValue value) {
    return new FilePort.Written(name, value.version(), value.sha256(), value.size());
  }

  private static Operation operation(State state, String id, String kind, String input) {
    Operation old = state.operations().get(id);
    if (old != null && (!old.kind().equals(kind) || !old.inputDigest().equals(input)))
      throw new FileFailure("FS_OPERATION_MISMATCH");
    // At most twenty tool calls, with one root and at most four file receipts per present.
    if (old == null && state.operations().size() >= 100) throw new FileFailure("WORKSPACE_LIMIT");
    return old;
  }

  private static long utf8Size(String content) {
    if (content == null || !StandardCharsets.UTF_8.newEncoder().canEncode(content))
      throw new FileFailure("INPUT_INVALID");
    return content.getBytes(StandardCharsets.UTF_8).length;
  }

  static String logicalPath(String path) {
    if (path == null
        || path.isBlank()
        || path.length() > 512
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains(":")
        || path.codePoints().anyMatch(c -> c < 32 || c == 127))
      throw new FileFailure("FS_PATH_DENIED");
    for (String component : path.split("/", -1))
      if (component.isEmpty() || component.equals(".") || component.equals(".."))
        throw new FileFailure("FS_PATH_DENIED");
    return path;
  }

  @FunctionalInterface
  private interface IO<T> {
    T apply(SecureDirectoryStream<Path> directory) throws IOException;
  }

  private <T> T locked(String scope, Cancellation cancellation, IO<T> work) {
    synchronized (LOCKS[Math.floorMod((root + scope).hashCode(), LOCKS.length)]) {
      cancellation.throwIfCancelled();
      try (var directory = openRoot();
          var channel =
              directory.newByteChannel(
                  Path.of(scope + ".lock"),
                  Set.of(
                      StandardOpenOption.CREATE,
                      StandardOpenOption.WRITE,
                      LinkOption.NOFOLLOW_LINKS),
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rw-------")))) {
        if (!(channel instanceof FileChannel file))
          throw new IOException("POSIX locking unavailable");
        try (var lock = file.lock()) {
          if (!lock.isValid()) throw new IOException("Workspace lock unavailable");
          cancellation.throwIfCancelled();
          return work.apply(directory);
        }
      } catch (IOException e) {
        throw new FileFailure("WORKSPACE_UNAVAILABLE", e);
      }
    }
  }

  private State load(SecureDirectoryStream<Path> directory, String scope) throws IOException {
    byte[] bytes;
    try (var file =
        directory.newByteChannel(
            Path.of(scope + ".json"), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (file.size() > MAX_STATE_BYTES) throw new IOException("Workspace state exceeds bound");
      ByteBuffer buffer = ByteBuffer.allocate((int) file.size());
      while (buffer.hasRemaining())
        if (file.read(buffer) < 0) throw new IOException("Truncated workspace");
      bytes = buffer.array();
    } catch (NoSuchFileException missing) {
      return new State(
          2, scope, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }
    try {
      Envelope envelope = STATE_JSON.readValue(bytes, Envelope.class);
      if (!Payloads.hash(envelope.payload()).equals(envelope.digest()))
        throw new IOException("Workspace integrity failure");
      State state = STATE_JSON.readValue(envelope.payload(), State.class);
      // Format 1 never persisted binding identity. Do not manufacture it from latest reads.
      if (state.format() == 1) throw new FileFailure("FS_BINDING_UNPROVEN");
      if ((state.format() != 2 && state.format() != 3)
          || !scope.equals(state.scope())
          || state.bindings() == null) throw new IOException("Workspace scope mismatch");
      for (FileValue value : state.files().values()) {
        verify(value);
        if ((state.format() == 3) != (value.modifiedAt() != null)
            || value.modifiedAt() != null && value.modifiedAt() <= 0)
          throw new IOException("Workspace timestamp integrity failure");
      }
      for (Operation operation : state.operations().values())
        if (operation.result() != null) verify(operation.result());
      return state;
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid workspace state", e);
    }
  }

  private static void verify(FileValue value) throws IOException {
    if (value.version() < 1
        || value.size() != utf8Size(value.content())
        || !Payloads.hash(value.content()).equals(value.sha256()))
      throw new IOException("Workspace file integrity failure");
  }

  private void save(SecureDirectoryStream<Path> directory, State state) throws IOException {
    save(directory, state, "STATE_BEFORE_RENAME");
  }

  private void save(SecureDirectoryStream<Path> directory, State state, String phase)
      throws IOException {
    String payload = Payloads.json(state);
    byte[] bytes =
        Payloads.json(new Envelope(Payloads.hash(payload), payload))
            .getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_STATE_BYTES) throw new FileFailure("WORKSPACE_LIMIT");
    Path temporary = Path.of(state.scope() + "." + UUID.randomUUID() + ".part");
    try {
      try (var channel =
          directory.newByteChannel(
              temporary,
              Set.of(
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
        if (!(channel instanceof FileChannel file))
          throw new IOException("POSIX durability unavailable");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) file.write(buffer);
        file.force(true);
      }
      boundary.hit(phase);
      directory.move(temporary, directory, Path.of(state.scope() + ".json"));
      forceDirectory(directory);
    } finally {
      try {
        directory.deleteFile(temporary);
      } catch (NoSuchFileException moved) {
        /* rename consumed it */
      }
    }
  }

  private static void forceDirectory(SecureDirectoryStream<Path> directory) throws IOException {
    try (var channel =
        directory.newByteChannel(
            Path.of("."), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (!(channel instanceof FileChannel file))
        throw new IOException("POSIX durability unavailable");
      file.force(true);
    }
  }

  /** Walk from the filesystem root using openat-style NOFOLLOW on every component. */
  private SecureDirectoryStream<Path> openRoot() throws IOException {
    DirectoryStream<Path> opened = java.nio.file.Files.newDirectoryStream(root.getRoot());
    if (!(opened instanceof SecureDirectoryStream<Path> current)) {
      opened.close();
      throw new IOException("SecureDirectoryStream is required");
    }
    try {
      for (Path part : root) {
        var next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
        current.close();
        current = next;
      }
      return current;
    } catch (IOException | RuntimeException e) {
      current.close();
      throw e;
    }
  }
}
