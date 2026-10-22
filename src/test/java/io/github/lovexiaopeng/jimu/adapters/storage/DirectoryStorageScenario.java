package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Real POSIX boundary checks, invoked in a foreground child by the acceptance driver. */
public final class DirectoryStorageScenario {
  private DirectoryStorageScenario() {}

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static void failure(String code, Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected " + code);
    } catch (FileFailure expected) {
      require(code.equals(expected.code()), "boundary returns " + code + ": " + expected.code());
    }
  }

  private static void write(SecureWorkspaceStore store, String task, String path, String content) {
    store.bind("tenant-a", task, UUID.randomUUID().toString(), () -> {}).read(path, 1, 2000);
    store.bind("tenant-a", task, UUID.randomUUID().toString(), () -> {}).write(path, content);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    String task = UUID.randomUUID().toString();
    var store = new SecureWorkspaceStore(root).logicalDirectory();
    store.initialize("tenant-a", task, true, () -> {});
    String scope = Payloads.hash("tenant-a:" + task);
    Path state = root.resolve(scope + ".json");
    byte[] initial = Files.readAllBytes(state);
    Path host = root.getParent().resolve("host-sentinel-" + task);
    Files.writeString(host, "host-unique-sentinel");
    String cwd = System.getProperty("user.dir");
    Path processCwd = Path.of("").toAbsolutePath();
    if (args[1].equals("boundaries")) {
      Files.delete(state);
      failure(
          "FS_BINDING_UNPROVEN", () -> store.directoryEvidence("tenant-a", task, "/", () -> {}));
      Files.createSymbolicLink(state, host);
      failure(
          "WORKSPACE_UNAVAILABLE", () -> store.directoryEvidence("tenant-a", task, "/", () -> {}));
      Files.delete(state);
      Files.write(state, initial);
      Path rootLink = root.getParent().resolve("workspace-link-" + task);
      Files.createSymbolicLink(rootLink, root);
      try {
        new SecureWorkspaceStore(rootLink).logicalDirectory();
        throw new AssertionError("Root symlink accepted");
      } catch (java.io.IOException expected) {
        require(true, "NOFOLLOW rejects symlink in root walk");
      } finally {
        Files.delete(rootLink);
      }
      write(store, task, "a/x", "one");
      var frozen = store.bind("tenant-a", task, UUID.randomUUID().toString(), () -> {});
      write(store, task, "a/x", "two");
      failure("FS_STALE_VERSION", () -> frozen.write("a/x", "stale"));
      require(Files.readString(host).equals("host-unique-sentinel"), "host sentinel is unchanged");
      require(
          cwd.equals(System.getProperty("user.dir"))
              && processCwd.equals(Path.of("").toAbsolutePath()),
          "Java and process cwd remain fixed");
    } else if (args[1].equals("limits")) {
      for (int index = 0; index < 8; index++) write(store, task, "f" + index, "x".repeat(32768));
      require(
          store.searchSnapshot("tenant-a", task, () -> {}).files().values().stream()
                  .mapToLong(SecureWorkspaceStore.FileValue::size)
                  .sum()
              == 262144,
          "inclusive 32 KiB file and 256 KiB current files accepted");
      failure("DIRECTORY_STORAGE_LIMIT", () -> write(store, task, "ninth", "x"));
      failure("DIRECTORY_STORAGE_LIMIT", () -> write(store, task, "f0", "x".repeat(32769)));
      byte[] valid = Files.readAllBytes(state);
      Files.write(state, new byte[2097153]);
      failure(
          "WORKSPACE_UNAVAILABLE", () -> store.directoryEvidence("tenant-a", task, "/", () -> {}));
      Files.write(state, valid);
      var parts = new java.util.ArrayList<Path>();
      for (int index = 0; index < 8; index++) {
        Path part = root.resolve(scope + "." + UUID.randomUUID() + ".part");
        Files.createFile(part);
        parts.add(part);
      }
      try {
        failure(
            "DIRECTORY_STORAGE_LIMIT",
            () ->
                store
                    .bind("tenant-a", task, UUID.randomUUID().toString(), () -> {})
                    .read("f0", 1, 2000));
      } finally {
        for (Path part : parts) Files.delete(part);
      }
      require(
          java.util.Arrays.equals(valid, Files.readAllBytes(state)),
          "rejected limits leave original envelope unchanged");
    } else throw new IllegalArgumentException("Unknown scenario");
    Files.delete(host);
    var plan = store.planPurge("tenant-a", task, () -> {});
    store.purge("tenant-a", task, plan, () -> {});
    store.purge("tenant-a", task, plan, () -> {});
    require(!Files.exists(state), "exact scope purge is idempotent");
    System.out.println("VERIFIED " + args[1]);
  }
}
