package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Trusted fixture setup through the real POSIX store, never through a fake search result. */
public final class WorkspaceSeedScenario {
  public static void main(String[] args) throws Exception {
    var store = new SecureWorkspaceStore(Path.of(args[0]));
    store.initialize(args[1], args[2], true, () -> {});
    var data =
        Payloads.read(
            Files.readString(Path.of(args[3])), com.fasterxml.jackson.databind.JsonNode.class);
    var fields = data.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      store
          .bind(args[1], args[2], UUID.randomUUID().toString(), () -> {})
          .read(entry.getKey(), 1, 2000);
      store
          .bind(args[1], args[2], UUID.randomUUID().toString(), () -> {})
          .write(entry.getKey(), entry.getValue().asText());
    }
    System.out.println("SEEDED " + store.searchSnapshot(args[1], args[2], () -> {}).digest());
  }
}
