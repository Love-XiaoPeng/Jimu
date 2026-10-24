package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs on a real POSIX filesystem with actual links and deterministic open races. */
public final class ImageStoreScenario {
  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
    System.out.println("PASS " + label);
  }

  private static void denied(Runnable operation, String label) {
    try {
      operation.run();
      throw new AssertionError(label);
    } catch (FileFailure expected) {
      check(
          expected.code().equals("IMAGE_UNAVAILABLE")
              || expected.code().equals("IMAGE_STORE_UNAVAILABLE"),
          label);
    }
  }

  public static void main(String[] args) throws Exception {
    Path parent = Path.of(args[0]);
    Path root =
        Files.createDirectory(
            parent.resolve("media"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var store = new SecureImageStore(root);
    String id = UUID.randomUUID().toString();
    byte[] bytes = "authorized-image-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String hash = Payloads.hash(bytes), key = store.write("tenant-a", id, bytes, hash);
    Path object = root.resolve(Payloads.hash("tenant-a") + "-" + key);
    check(
        Arrays.equals(bytes, store.read("tenant-a", key, hash, bytes.length)),
        "actual immutable bytes roundtrip");
    check(
        store.write("tenant-a", id, bytes, hash).equals(key),
        "identical immutable publication retries");
    denied(() -> store.read("tenant-b", key, hash, bytes.length), "tenant scoped physical name");
    denied(() -> store.read("tenant-a", key, hash, bytes.length - 1), "declared size mismatch");
    denied(
        () -> store.read("tenant-a", key, Payloads.hash("wrong"), bytes.length), "digest mismatch");
    Path sentinel = parent.resolve("sentinel");
    byte[] secret = "outside-secret-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(sentinel, secret);
    Path saved = root.resolve("saved");
    Files.move(object, saved);
    Files.createSymbolicLink(object, sentinel);
    denied(() -> store.read("tenant-a", key, hash, bytes.length), "final symlink refused");
    Files.delete(object);
    Files.createLink(object, sentinel);
    denied(() -> store.read("tenant-a", key, hash, bytes.length), "hardlink refused");
    Files.delete(object);
    Files.move(saved, object);
    for (String point : java.util.List.of("before-open", "after-open")) {
      var once = new AtomicBoolean();
      var racing =
          new SecureImageStore(
              root,
              hit -> {
                if (hit.equals(point) && once.compareAndSet(false, true)) {
                  try {
                    Files.move(object, saved);
                    Files.copy(sentinel, object);
                  } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                  }
                }
              });
      denied(
          () -> racing.read("tenant-a", key, hash, bytes.length),
          point + " inode replacement refused");
      Files.delete(object);
      Files.move(saved, object);
    }
    var fifo =
        new SecureImageStore(
            root,
            hit -> {
              if (hit.equals("before-open")) {
                try {
                  Files.move(object, saved);
                  var process = new ProcessBuilder("mkfifo", object.toString()).start();
                  if (process.waitFor() != 0) throw new AssertionError("mkfifo");
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                } catch (InterruptedException error) {
                  throw new IllegalStateException(error);
                }
              }
            });
    long started = System.nanoTime();
    denied(
        () -> fifo.read("tenant-a", key, hash, bytes.length),
        "FIFO replacement refused without blocking");
    check(
        System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2),
        "FIFO bounded open time");
    Files.delete(object);
    Files.move(saved, object);
    Path renamed = parent.resolve("renamed");
    var moved = new AtomicBoolean();
    var rename =
        new SecureImageStore(
            root,
            hit -> {
              if (hit.equals("before-open") && moved.compareAndSet(false, true)) {
                try {
                  Files.move(root, renamed);
                  Files.createSymbolicLink(root, parent);
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
              }
            });
    check(
        Arrays.equals(bytes, rename.read("tenant-a", key, hash, bytes.length)),
        "ancestor rename keeps pinned directory authority");
    denied(store::check, "root symlink rejected on next open");
    Files.delete(root);
    Files.move(renamed, root);
    Path alias = parent.resolve("alias");
    Files.createSymbolicLink(alias, parent);
    denied(
        () -> {
          try {
            new SecureImageStore(alias.resolve("media"));
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        },
        "symlink in ancestor rejected");
    check(
        Arrays.equals(secret, Files.readAllBytes(sentinel)), "outside sentinel remains byte exact");
    store.removeUpload("tenant-a", id);
    check(!Files.exists(object), "exact upload cleanup");
  }
}
