package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Private, handle-relative immutable media namespace. No logical input path becomes an OS path. */
public final class SecureImageStore implements ArtifactStore {
  private final Path root;
  private final java.util.function.Consumer<String> boundary;

  public SecureImageStore(Path root) throws IOException {
    this(root, point -> {});
  }

  public SecureImageStore(Path root, java.util.function.Consumer<String> boundary)
      throws IOException {
    this.root = root.toAbsolutePath().normalize();
    this.boundary = boundary;
    check();
  }

  private SecureDirectoryStream<Path> open() throws IOException {
    var opened = Files.newDirectoryStream(root.getRoot());
    if (!(opened instanceof SecureDirectoryStream<Path> current)) {
      opened.close();
      throw new IOException("SecureDirectoryStream required");
    }
    try {
      for (Path part : root) {
        var next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
        current.close();
        current = next;
      }
      var permissions =
          current.getFileAttributeView(PosixFileAttributeView.class).readAttributes().permissions();
      if (!permissions.equals(PosixFilePermissions.fromString("rwx------")))
        throw new IOException("Private image root required");
      return current;
    } catch (IOException | RuntimeException e) {
      current.close();
      throw e;
    }
  }

  private static void force(SecureDirectoryStream<Path> dir) throws IOException {
    try (var channel =
        dir.newByteChannel(
            Path.of("."), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (!(channel instanceof FileChannel file)) throw new IOException("Durability unavailable");
      file.force(true);
    }
  }

  /** Linux proc alias resolves the already-open directory, including after an ancestor rename. */
  private static Path anchor(SecureDirectoryStream<Path> dir) throws IOException {
    Object key = dir.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
    try (var fds = Files.list(Path.of("/proc/self/fd"))) {
      for (Path fd : fds.toList()) {
        try {
          if (Objects.equals(
              key,
              Files.readAttributes(fd, java.nio.file.attribute.BasicFileAttributes.class)
                  .fileKey())) return fd;
        } catch (NoSuchFileException closed) {
          /* descriptor enumeration is not atomic */
        }
      }
    }
    throw new IOException("Pinned directory unavailable");
  }

  private static Path name(String tenant, String key) {
    if (!key.matches("[a-f0-9-]{36}-[a-f0-9]{64}"))
      throw new FileFailure(ImageErrorCode.IMAGE_UNAVAILABLE);
    return Path.of(Payloads.hash(tenant) + "-" + key);
  }

  private byte[] read(SecureDirectoryStream<Path> dir, Path name, String hash, long size)
      throws IOException {
    if (size < 1 || size > 1048576) throw new IOException("Image bound exceeded");
    var before =
        dir.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
    Path anchored = anchor(dir).resolve(name);
    if (!before.isRegularFile()
        || before.size() != size
        || ((Number) Files.getAttribute(anchored, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                .longValue()
            != 1) throw new IOException("Invalid media object");
    boundary.accept("before-open");
    try (var channel =
        // O_RDWR avoids blocking forever if a FIFO replaces the checked regular file.
        // Media objects are worker-owned 0600; this channel never performs a write.
        dir.newByteChannel(
            name,
            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
      boundary.accept("after-open");
      if (channel.size() != size) throw new IOException("Image size mismatch");
      var bytes = ByteBuffer.allocate((int) size + 1);
      while (bytes.hasRemaining()) if (channel.read(bytes) < 0) break;
      var after =
          dir.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
      if (bytes.position() != size
          || !after.isRegularFile()
          || !Objects.equals(before.fileKey(), after.fileKey())
          || ((Number) Files.getAttribute(anchored, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                  .longValue()
              != 1) throw new IOException("Image replaced");
      byte[] result = java.util.Arrays.copyOf(bytes.array(), (int) size);
      if (!Payloads.hash(result).equals(hash)) throw new IOException("Image digest mismatch");
      return result;
    }
  }

  @Override
  public byte[] read(String tenant, String key, String hash, long size) {
    try (var dir = open()) {
      return read(dir, name(tenant, key), hash, size);
    } catch (IOException e) {
      throw new FileFailure(ImageErrorCode.IMAGE_UNAVAILABLE);
    }
  }

  @Override
  public String write(String tenant, String uploadId, byte[] bytes, String hash) {
    if (!UUID.fromString(uploadId).toString().equals(uploadId)
        || bytes.length < 1
        || bytes.length > 1048576
        || !Payloads.hash(bytes).equals(hash))
      throw new IllegalArgumentException("Invalid media object");
    String key = uploadId + "-" + hash;
    Path destination = name(tenant, key),
        temporary =
            Path.of(Payloads.hash(tenant) + "-" + uploadId + "." + UUID.randomUUID() + ".part");
    try (var dir = open()) {
      try {
        read(dir, destination, hash, bytes.length);
        return key;
      } catch (NoSuchFileException missing) {
        /* first immutable publication */
      }
      try {
        try (var channel =
            dir.newByteChannel(
                temporary,
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {
          if (!(channel instanceof FileChannel file))
            throw new IOException("Durability unavailable");
          var buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) file.write(buffer);
          file.force(true);
        }
        dir.move(temporary, dir, destination);
        force(dir);
        read(dir, destination, hash, bytes.length);
        return key;
      } finally {
        try {
          dir.deleteFile(temporary);
        } catch (NoSuchFileException moved) {
          /* published */
        }
      }
    } catch (IOException e) {
      throw new FileFailure(ImageErrorCode.IMAGE_UNAVAILABLE);
    }
  }

  @Override
  public void removeUpload(String tenant, String uploadId) {
    if (!UUID.fromString(uploadId).toString().equals(uploadId))
      throw new IllegalArgumentException("Invalid media upload");
    String prefix = Payloads.hash(tenant) + "-" + uploadId;
    try (var dir = open()) {
      for (Path path : dir) {
        String file = path.getFileName().toString();
        if (file.startsWith(prefix + "-") || file.startsWith(prefix + "."))
          dir.deleteFile(Path.of(file));
      }
      force(dir);
    } catch (IOException e) {
      throw new FileFailure(ImageErrorCode.IMAGE_UNAVAILABLE);
    }
  }

  @Override
  public void check() {
    try (var dir = open()) {
      anchor(dir);
      force(dir);
    } catch (IOException e) {
      throw new FileFailure(ImageErrorCode.IMAGE_STORE_UNAVAILABLE);
    }
  }
}
