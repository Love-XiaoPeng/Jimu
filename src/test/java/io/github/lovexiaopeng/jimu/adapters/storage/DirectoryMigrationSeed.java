package io.github.lovexiaopeng.jimu.adapters.storage;

import java.nio.file.Path;
import java.util.UUID;

/** Runs against the old JAR's real store to create both supported historical formats. */
public final class DirectoryMigrationSeed {
  private DirectoryMigrationSeed() {}

  public static void main(String[] args) throws Exception {
    var store = new SecureWorkspaceStore(Path.of(args[0]));
    store.initialize("tenant-a", args[1], Boolean.parseBoolean(args[2]), () -> {});
    store
        .bind("tenant-a", args[1], UUID.randomUUID().toString(), () -> {})
        .read("old/file", 1, 2000);
    store
        .bind("tenant-a", args[1], UUID.randomUUID().toString(), () -> {})
        .write("old/file", "historical-format-" + args[2]);
    System.out.println("SEEDED");
  }
}
