package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Foreground native filesystem/process tests, launched by WorkspaceIT on both entry platforms. */
public final class WorkspaceScenario {
  private static final String TASK = "10000000-0000-0000-0000-000000000001";
  private static final List<String> RESULTS = new ArrayList<>();

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    RESULTS.add(name);
    System.out.println("PASS " + name);
  }

  private static void failure(String code, Runnable operation) {
    try {
      operation.run();
      throw new AssertionError("Expected " + code);
    } catch (FileFailure error) {
      check(code.equals(error.code()), code);
    }
  }

  private static FilePort port(SecureWorkspaceStore store) {
    return store.bind("tenant-a", TASK, UUID.randomUUID().toString(), () -> {});
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    if (args.length > 2 && args[2].equals("concurrent")) {
      var store = new SecureWorkspaceStore(root);
      var bound = port(store);
      Path gate = Path.of(args[3]);
      Files.writeString(gate.resolveSibling(gate.getFileName() + ".ready"), "ready");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!Files.exists(gate)) {
        if (System.nanoTime() > deadline) throw new AssertionError("Process gate timed out");
        Thread.sleep(10);
      }
      try {
        bound.write("process-race", "writer-" + args[4]);
      } catch (FileFailure error) {
        if (!error.code().equals("FS_STALE_VERSION")) throw error;
        System.exit(74);
      }
      return;
    }
    if (args.length > 2 && args[2].equals("child")) {
      String phase = args[3], op = args[4];
      var store =
          new SecureWorkspaceStore(
              root,
              name -> {
                if (name.equals(phase)) Runtime.getRuntime().halt(73);
              });
      store.bind("tenant-a", TASK, op, () -> {}).write("crash.md", "after crash");
      throw new AssertionError("Crash boundary not reached");
    }
    Path evidence = Path.of(args[1]);
    try {
      var store = new SecureWorkspaceStore(root);
      var preflight = store.inspect("tenant-a", TASK, "report.md", () -> {});
      check(
          preflight.version() == 0 && preflight.size() == 0,
          "host preflight sees actual missing file");
      // A host inspection must not create the model observation required by CAS admission.
      failure("FS_NOT_OBSERVED", () -> port(store).write("report.md", "unobserved"));
      var missing = port(store).read("report.md", 1, 2000);
      check(
          !missing.exists() && missing.version() == 0 && missing.size() == 0,
          "durable missing observation");
      String firstOperation = UUID.randomUUID().toString();
      FilePort first = store.bind("tenant-a", TASK, firstOperation, () -> {});
      var written = first.write("report.md", "第一行\nsecond\n");
      check(written.version() == 1 && written.size() == 17, "UTF8 version/hash/byte size");
      check(written.sha256().equals(Payloads.hash("第一行\nsecond\n")), "content hash matches bytes");
      var page = port(store).read("report.md", 2, 1);
      check(
          page.content().equals("2: second\n") && page.totalLines() == 3,
          "1-based bounded pagination");
      check(
          port(store).read("report.md", Integer.MAX_VALUE, 2000).content().isEmpty(),
          "out of range page is empty without integer overflow");
      check(
          first.write("report.md", "第一行\nsecond\n").equals(written),
          "stable host operation is idempotent");
      failure("FS_OPERATION_MISMATCH", () -> first.write("report.md", "changed arguments"));
      failure("FS_OPERATION_MISMATCH", () -> first.read("report.md", 1, 2000));
      String readOperation = UUID.randomUUID().toString();
      FilePort oldRead = store.bind("tenant-a", TASK, readOperation, () -> {});
      var observed = oldRead.read("report.md", 1, 2000);
      FilePort present = port(store);
      var snapshot = present.snapshot("report.md");
      failure("FS_OPERATION_MISMATCH", () -> present.write("report.md", "wrong invocation kind"));
      failure("FS_OPERATION_MISMATCH", () -> first.snapshot("report.md"));
      var replaced = port(store).write("report.md", "new");
      check(replaced.version() == 2, "replacement increments version");
      check(
          present.snapshot("report.md").equals(snapshot),
          "present operation pins exact original bytes/version");
      check(
          oldRead.read("report.md", 1, 2000).equals(observed),
          "read replay returns original receipt without new observation");
      check(
          first.write("report.md", "第一行\nsecond\n").equals(written),
          "write recovery returns original receipt after subsequent replacement");
      check(
          port(store).read("report.md", 1, 2000).version() == 2,
          "old write replay never rolls back latest version");
      check(
          !store
              .bind("tenant-b", TASK, UUID.randomUUID().toString(), () -> {})
              .read("report.md", 1, 2000)
              .exists(),
          "tenant isolation at actual open");
      check(
          !store
              .bind(
                  "tenant-a", UUID.randomUUID().toString(), UUID.randomUUID().toString(), () -> {})
              .read("report.md", 1, 2000)
              .exists(),
          "task isolation at actual open");
      for (String path :
          List.of(
              "/etc/passwd",
              "../outside",
              "x/../y",
              "./x",
              "x//y",
              "x/",
              "C:/x",
              "x\\y",
              "x\u0000y",
              "")) failure("FS_PATH_DENIED", () -> port(store).read(path, 1, 2000));
      failure("INPUT_INVALID", () -> port(store).read("report.md", 0, 1));
      failure("INPUT_INVALID", () -> port(store).read("report.md", 1, 2001));
      failure("INPUT_INVALID", () -> port(store).write("bad", "\ud800"));
      port(store).read("empty", 1, 2000);
      check(port(store).write("empty", "").size() == 0, "empty writes are valid");
      check(port(store).read("empty", 1, 2000).exists(), "empty file differs from missing");
      failure("FS_NOT_OBSERVED", () -> port(store).snapshot("not-read"));
      port(store).read("missing", 1, 2000);
      failure("FS_NOT_FOUND", () -> port(store).snapshot("missing"));

      // Exact process-exit windows, each against persisted bytes, not a mock state transition.
      port(store).read("crash.md", 1, 2000);
      String intentOperation = UUID.randomUUID().toString();
      crash(root, evidence, "WRITE_INTENT_DURABLE", intentOperation);
      var afterIntent = new SecureWorkspaceStore(root);
      check(
          !port(afterIntent).read("crash.md", 1, 2000).exists(),
          "exit after durable intent leaves original file missing");
      var independent = port(afterIntent).write("crash.md", "independent");
      failure(
          "FS_STALE_VERSION",
          () ->
              afterIntent
                  .resume("tenant-a", TASK, intentOperation, () -> {})
                  .write("crash.md", "after crash"));
      check(
          port(afterIntent).read("crash.md", 1, 2000).sha256().equals(independent.sha256()),
          "recovered old intent cannot adopt a newer read and overwrite");
      String committedOperation = UUID.randomUUID().toString();
      crash(root, evidence, "WRITE_COMMITTED", committedOperation);
      var afterCommit = new SecureWorkspaceStore(root);
      var recovered =
          afterCommit
              .resume("tenant-a", TASK, committedOperation, () -> {})
              .write("crash.md", "after crash");
      check(
          recovered.version() == 2 && port(afterCommit).read("crash.md", 1, 2000).version() == 2,
          "exit after durable commit recovers one version and original receipt");
      String beforeRename = UUID.randomUUID().toString();
      crash(root, evidence, "STATE_BEFORE_RENAME", beforeRename);
      var afterBeforeRename = new SecureWorkspaceStore(root);
      check(
          port(afterBeforeRename).read("crash.md", 1, 2000).version() == 2,
          "exit with fsynced temp before rename keeps complete previous state");
      failure(
          "FS_BINDING_UNPROVEN",
          () -> afterBeforeRename.resume("tenant-a", TASK, beforeRename, () -> {}));
      check(
          port(afterBeforeRename).write("crash.md", "after crash").version() == 3,
          "new admission after rejected unproven recovery performs CAS once");
      check(
          port(afterBeforeRename).read("crash.md", 1, 2000).sha256().equals(recovered.sha256()),
          "same bytes still create a new version and do not hide ABA");

      port(store).read("cancel-race", 1, 2000);
      var cancelledDuringWrite = new java.util.concurrent.atomic.AtomicBoolean();
      String cancelledOperation = UUID.randomUUID().toString();
      var cancellingStore =
          new SecureWorkspaceStore(
              root,
              name -> {
                if (name.equals("WRITE_INTENT_DURABLE")) cancelledDuringWrite.set(true);
              });
      try {
        cancellingStore
            .bind(
                "tenant-a",
                TASK,
                cancelledOperation,
                () -> {
                  if (cancelledDuringWrite.get())
                    throw new java.util.concurrent.CancellationException();
                })
            .write("cancel-race", "retained intent");
        throw new AssertionError("Mid-operation cancellation not honored");
      } catch (java.util.concurrent.CancellationException expected) {
        check(
            !port(store).read("cancel-race", 1, 2000).exists(),
            "cancel after intent prevents content commit");
      }
      check(
          new SecureWorkspaceStore(root)
                  .resume("tenant-a", TASK, cancelledOperation, () -> {})
                  .write("cancel-race", "retained intent")
                  .version()
              == 1,
          "normal recovery of cancelled file operation uses original intent once");

      // Two separate host instances share the kernel lock. A previously pinned intent must lose.
      port(store).read("race", 1, 2000);
      CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
      var executor = Executors.newFixedThreadPool(2);
      try {
        var futures = new ArrayList<java.util.concurrent.Future<Long>>();
        for (int i = 0; i < 2; i++) {
          String text = "value-" + i;
          futures.add(
              executor.submit(
                  () -> {
                    var other = new SecureWorkspaceStore(root);
                    var bound = port(other);
                    ready.countDown();
                    start.await();
                    try {
                      return bound.write("race", text).version();
                    } catch (FileFailure error) {
                      if (!error.code().equals("FS_STALE_VERSION")) throw error;
                      return -1L;
                    }
                  }));
        }
        check(ready.await(5, TimeUnit.SECONDS), "parallel host instances ready");
        start.countDown();
        var versions =
            List.of(
                futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        check(
            versions.contains(1L) && versions.contains(-1L),
            "concurrent writes bound to same observation have one CAS winner and one stale result");
      } finally {
        executor.shutdownNow();
        check(executor.awaitTermination(5, TimeUnit.SECONDS), "parallel threads drained");
      }

      port(store).read("process-race", 1, 2000);
      var processes = new ArrayList<Process>();
      var gates = List.of(evidence.resolve("writer-a.go"), evidence.resolve("writer-b.go"));
      try {
        for (int i = 0; i < gates.size(); i++) {
          var command =
              List.of(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-Xmx768m",
                  "-cp",
                  System.getProperty("java.class.path"),
                  WorkspaceScenario.class.getName(),
                  root.toString(),
                  evidence.toString(),
                  "concurrent",
                  gates.get(i).toString(),
                  String.valueOf(i));
          processes.add(
              new ProcessBuilder(command)
                  .redirectErrorStream(true)
                  .redirectOutput(evidence.resolve("writer-" + i + ".log").toFile())
                  .start());
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (gates.stream()
            .anyMatch(g -> !Files.exists(g.resolveSibling(g.getFileName() + ".ready")))) {
          if (System.nanoTime() > until) throw new AssertionError("Concurrent processes not ready");
          Thread.sleep(10);
        }
        for (Path gate : gates) Files.writeString(gate, "go");
        for (Process process : processes)
          check(process.waitFor(20, TimeUnit.SECONDS), "concurrent writer process exited");
        var exits = processes.stream().map(Process::exitValue).toList();
        check(
            exits.contains(0) && exits.contains(74),
            "separate JVMs enforce one CAS winner at actual open boundary");
        check(
            port(store).read("process-race", 1, 2000).version() == 1,
            "process race produces exactly one file version");
      } finally {
        for (Process process : processes)
          if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor();
          }
        Files.writeString(
            evidence.resolve("concurrent-processes.json"),
            Payloads.json(
                processes.stream()
                    .map(
                        p ->
                            java.util.Map.of(
                                "pid", p.pid(), "alive", p.isAlive(), "exit", p.exitValue()))
                    .toList()));
      }

      // A state symlink must fail at open; a scope swap must fail integrity checking.
      String scope = Payloads.hash("tenant-a:" + TASK);
      Path state = root.resolve(scope + ".json"), saved = root.resolve("saved-state");
      Files.move(state, saved);
      Path outside = root.getParent().resolve("outside");
      Files.writeString(outside, "outside-unchanged");
      Files.createSymbolicLink(state, outside);
      failure("WORKSPACE_UNAVAILABLE", () -> port(store).read("report.md", 1, 2000));
      check(
          Files.readString(outside).equals("outside-unchanged"),
          "state symlink never follows outside file");
      Files.delete(state);
      Files.move(saved, state);
      String otherScope = Payloads.hash("tenant-b:" + TASK);
      Files.copy(
          state,
          root.resolve(otherScope + ".json"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      failure(
          "WORKSPACE_UNAVAILABLE",
          () ->
              store
                  .bind("tenant-b", TASK, UUID.randomUUID().toString(), () -> {})
                  .read("report.md", 1, 2000));
      Files.delete(root.resolve(otherScope + ".json"));
      String pristine = Files.readString(state);
      Files.writeString(state, pristine.replace("after crash", "tampered"));
      failure("WORKSPACE_UNAVAILABLE", () -> port(store).read("crash.md", 1, 2000));
      Files.writeString(state, pristine);

      Path lockFile = root.resolve(scope + ".lock");
      Files.delete(lockFile);
      Files.createSymbolicLink(lockFile, outside);
      failure("WORKSPACE_UNAVAILABLE", () -> port(store).read("report.md", 1, 2000));
      check(
          Files.readString(outside).equals("outside-unchanged"),
          "lock symlink never opens outside file");
      Files.delete(lockFile);

      Path renamed = root.resolveSibling("renamed-workspace");
      var moved = new java.util.concurrent.atomic.AtomicBoolean();
      var raceStore =
          new SecureWorkspaceStore(
              root,
              name -> {
                if (name.equals("STATE_BEFORE_RENAME") && moved.compareAndSet(false, true)) {
                  try {
                    Files.move(root, renamed);
                    Files.createSymbolicLink(root, root.getParent().resolve("decoy"));
                  } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                  }
                }
              });
      Path decoy = root.getParent().resolve("decoy");
      Files.createDirectory(
          decoy,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      port(raceStore).read("root-swap", 1, 2000);
      try (var entries = Files.list(decoy)) {
        check(
            moved.get() && entries.findAny().isEmpty(),
            "root swap cannot redirect pinned file-open or rename");
      }
      failure("WORKSPACE_UNAVAILABLE", () -> port(store).read("root-swap", 1, 2000));
      Files.delete(root);
      Files.move(renamed, root);
      check(
          !port(store).read("root-swap", 1, 2000).exists(),
          "pinned root state remains readable after restoring directory");

      // Fresh task for exact quota boundaries, so retained operation snapshots cannot mask totals.
      String quotaTask = UUID.randomUUID().toString();
      var quota = store.bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {});
      quota.read("large", 1, 1);
      var qwrite = store.bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {});
      check(
          qwrite.write("large", "x".repeat(SecureWorkspaceStore.MAX_BYTES - 1)).size()
              == SecureWorkspaceStore.MAX_BYTES - 1L,
          "workspace N-1 bytes accepted");
      check(
          store
                  .bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {})
                  .write("large", "x".repeat(SecureWorkspaceStore.MAX_BYTES))
                  .size()
              == SecureWorkspaceStore.MAX_BYTES,
          "workspace N bytes accepted");
      failure(
          "WORKSPACE_LIMIT",
          () ->
              store
                  .bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {})
                  .write("large", "x".repeat(SecureWorkspaceStore.MAX_BYTES + 1)));
      store.bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {}).read("extra", 1, 1);
      failure(
          "WORKSPACE_LIMIT",
          () ->
              store
                  .bind("tenant-a", quotaTask, UUID.randomUUID().toString(), () -> {})
                  .write("extra", "x"));
      try {
        store
            .bind(
                "tenant-a",
                TASK,
                UUID.randomUUID().toString(),
                () -> {
                  throw new java.util.concurrent.CancellationException();
                })
            .write("report.md", "cancelled");
        throw new AssertionError("Cancellation not honored");
      } catch (java.util.concurrent.CancellationException expected) {
        check(true, "cancelled host cannot enter workspace operation");
      }
      WorkspaceRecoveryScenario.run(root, evidence.resolve("r6"));
    } finally {
      Files.writeString(evidence.resolve("assertions.json"), Payloads.json(RESULTS));
    }
  }

  private static void crash(Path root, Path evidence, String phase, String operation)
      throws Exception {
    var command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx768m",
            "-cp",
            System.getProperty("java.class.path"),
            WorkspaceScenario.class.getName(),
            root.toString(),
            evidence.toString(),
            "child",
            phase,
            operation);
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve(phase + ".log").toFile())
            .start();
    try {
      check(process.waitFor(20, TimeUnit.SECONDS), phase + " child exits in foreground");
      check(process.exitValue() == 73, phase + " exact process exit reached");
      Files.writeString(
          evidence.resolve(phase + ".json"),
          Payloads.json(
              java.util.Map.of(
                  "pid",
                  process.pid(),
                  "alive",
                  process.isAlive(),
                  "exit",
                  process.exitValue(),
                  "operationId",
                  operation)));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }
}
