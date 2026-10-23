package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Single-host ext4 store. A hard link publishes a closed, fsynced object without replacement. */
public final class LocalArtifactStore implements ArtifactStore {
  private final Path root;

  public LocalArtifactStore(Path root) throws IOException {
    this.root = root.toRealPath();
    check();
  }

  private Path directory(String tenant) throws IOException {
    Path directory = root.resolve(Payloads.hash(tenant));
    try {
      Files.createDirectory(directory);
    } catch (FileAlreadyExistsException e) {
      /* existing tenant namespace */
    }
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Invalid namespace");
    return directory;
  }

  @Override
  public String write(String tenant, String uploadId, byte[] bytes, String hash) {
    if (!UUID.fromString(uploadId).toString().equals(uploadId)
        || bytes.length > 1048576
        || !Payloads.hash(bytes).equals(hash)) throw new IllegalArgumentException("Invalid upload");
    try {
      Path dir = directory(tenant),
          temporary = dir.resolve(uploadId + "." + UUID.randomUUID() + ".part");
      String key = uploadId + "-" + hash;
      Path snapshot = dir.resolve(key);
      try {
        try (var file =
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) file.write(buffer);
          file.force(true);
        }
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("r--------"));
        try {
          Files.createLink(snapshot, temporary);
        } catch (FileAlreadyExistsException e) {
          read(tenant, key, hash, bytes.length);
        }
        try (var directory = FileChannel.open(dir, StandardOpenOption.READ)) {
          directory.force(true);
        }
        return key;
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Artifact write failed", e);
    }
  }

  @Override
  public byte[] read(String tenant, String key, String hash, long size) {
    if (!key.matches("[a-f0-9-]{36}-[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid snapshot key");
    try {
      Path snapshot = directory(tenant).resolve(key);
      if (!Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)
          || Files.size(snapshot) != size
          || size > 1048576) throw new IOException("Invalid snapshot");
      byte[] bytes = Files.readAllBytes(snapshot);
      if (!Payloads.hash(bytes).equals(hash)) throw new IOException("Snapshot integrity failure");
      return bytes;
    } catch (IOException e) {
      throw new IllegalStateException("Artifact unavailable", e);
    }
  }

  @Override
  public void check() {
    try {
      if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
          || !Files.isWritable(root)
          || !Files.getFileStore(root).supportsFileAttributeView("posix"))
        throw new IllegalStateException("Required POSIX artifact store unavailable");
    } catch (IOException e) {
      throw new IllegalStateException("Artifact store unavailable", e);
    }
  }

  @Override
  public void removeUpload(String tenant, String uploadId) {
    if (!UUID.fromString(uploadId).toString().equals(uploadId))
      throw new IllegalArgumentException("Invalid upload");
    try (var files = Files.list(directory(tenant))) {
      for (Path path :
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith(uploadId + ".")
                          || p.getFileName().toString().startsWith(uploadId + "-"))
              .toList()) Files.delete(path);
    } catch (IOException e) {
      throw new IllegalStateException("Artifact cleanup failed", e);
    }
  }
}
