package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Private operator-owned roots; all reopening, publication and deletion are handle relative. */
public final class SearchFiles {
  private SearchFiles() {}

  public static final Set<StandardOpenOption> READ = Set.of(StandardOpenOption.READ);

  public static SecureDirectoryStream<Path> open(Path path) throws IOException {
    path = path.toAbsolutePath().normalize();
    DirectoryStream<Path> first = Files.newDirectoryStream(path.getRoot());
    if (!(first instanceof SecureDirectoryStream<Path> current)) {
      first.close();
      throw new IOException("Secure directories required");
    }
    try {
      for (Path part : path) {
        var next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
        current.close();
        current = next;
      }
      return current;
    } catch (IOException | RuntimeException error) {
      current.close();
      throw error;
    }
  }

  public static void privateRoot(Path path) throws IOException {
    try (var root = open(path)) {
      if (!root.getFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)
          .readAttributes()
          .permissions()
          .equals(PosixFilePermissions.fromString("rwx------")))
        throw new IOException("Private search root required");
    }
  }

  public static String[] identity(Path path) throws IOException {
    return new String[] {
      Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS).toString(),
      Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS).toString()
    };
  }

  public static void write(Path root, String name, byte[] bytes) throws IOException {
    try (var directory = open(root);
        var channel =
            directory.newByteChannel(
                Path.of(name),
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {
      if (!(channel instanceof FileChannel file))
        throw new IOException("POSIX durability required");
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
      force(directory);
    }
  }

  public static byte[] read(Path root, String name, int bound) throws IOException {
    try (var directory = open(root);
        var file =
            directory.newByteChannel(
                Path.of(name), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (file.size() > bound) throw new IOException("Search object exceeds bound");
      var buffer = ByteBuffer.allocate((int) file.size());
      while (buffer.hasRemaining())
        if (file.read(buffer) < 0) throw new IOException("Truncated search object");
      return buffer.array();
    }
  }

  public static void project(
      Path root, SecureWorkspaceStore.WorkspaceSnapshot snapshot, Runnable checkpoint)
      throws IOException {
    if (snapshot.files().size() > 4096) throw new FileFailure("SEARCH_WORKSPACE_LIMIT");
    Set<String> directories = new HashSet<>();
    long total = 0, metadata = 0;
    for (var entry : snapshot.files().entrySet()) {
      String path = entry.getKey();
      NativeSearchQuery.path(path);
      total += entry.getValue().size();
      metadata += path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 160;
      String[] parts = path.split("/");
      if (parts.length > 512 || total > SecureWorkspaceStore.MAX_BYTES || metadata > 8388608)
        throw new FileFailure("SEARCH_WORKSPACE_LIMIT");
      String parent = "";
      for (int i = 0; i < parts.length - 1; i++) {
        parent = parent.isEmpty() ? parts[i] : parent + "/" + parts[i];
        if (snapshot.files().containsKey(parent))
          throw new FileFailure("SEARCH_WORKSPACE_UNREPRESENTABLE");
        directories.add(parent);
      }
    }
    if (directories.size() > 8192) throw new FileFailure("SEARCH_WORKSPACE_LIMIT");
    for (String name :
        directories.stream()
            .sorted(java.util.Comparator.comparingInt(s -> s.split("/").length))
            .toList()) {
      checkpoint.run();
      Path directory = root.resolve(name);
      Files.createDirectory(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      try (var pinned = open(directory)) {
        force(pinned);
      }
    }
    for (var entry : snapshot.files().entrySet()) {
      checkpoint.run();
      Path path = root.resolve(entry.getKey());
      write(
          path.getParent(),
          path.getFileName().toString(),
          entry.getValue().content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try (var parent = open(path.getParent())) {
        parent
            .getFileAttributeView(
                path.getFileName(),
                java.nio.file.attribute.BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS)
            .setTimes(
                FileTime.from(entry.getValue().modifiedAt(), TimeUnit.NANOSECONDS), null, null);
      }
    }
    // JDK POSIX timestamp setters may round to microseconds. The launcher restores the exact
    // persisted nanoseconds through pinned file descriptors before it exposes the read-only tree.
    var metadataBytes = new java.io.ByteArrayOutputStream();
    try (var metadataOut = new java.io.DataOutputStream(metadataBytes)) {
      metadataOut.writeInt(snapshot.files().size());
      for (var entry : snapshot.files().entrySet()) {
        byte[] name = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] modified =
            entry
                .getValue()
                .modifiedAt()
                .toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        metadataOut.writeInt(name.length);
        metadataOut.write(name);
        metadataOut.writeInt(modified.length);
        metadataOut.write(modified);
      }
    }
    write(root.getParent(), "mtimes", metadataBytes.toByteArray());
  }

  public static void removeTree(Path path) throws IOException {
    try (var parent = open(path.getParent());
        var directory = parent.newDirectoryStream(path.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
      removeContents(directory);
      parent.deleteDirectory(path.getFileName());
      force(parent);
    }
  }

  private static void removeContents(SecureDirectoryStream<Path> directory) throws IOException {
    for (Path entry : directory) {
      Path name = entry.getFileName();
      var attributes =
          directory
              .getFileAttributeView(
                  name,
                  java.nio.file.attribute.BasicFileAttributeView.class,
                  LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
      if (attributes.isDirectory()) {
        try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
          removeContents(child);
        }
        directory.deleteDirectory(name);
      } else directory.deleteFile(name);
    }
  }

  private static void force(SecureDirectoryStream<Path> directory) throws IOException {
    try (var channel =
        directory.newByteChannel(
            Path.of("."), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (!(channel instanceof FileChannel file))
        throw new IOException("Directory durability required");
      file.force(true);
    }
  }
}
