package io.github.lovexiaopeng.jimu.adapters.storage;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real JVM exits/recovery with competing content, not a simulated in-memory rebind. */
public final class WorkspaceRecoveryScenario {
  private static final List<String> ASSERTIONS = new ArrayList<>();
  private static final List<Map<String, Object>> PROCESSES = new ArrayList<>();
  private static final List<Map<String, Object>> FACTS = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]), result = Path.of(args[1]);
    String mode = args[2], task = args[3], id = args[4], phase = args[5];
    var store =
        new SecureWorkspaceStore(
            root,
            name -> {
              if (name.equals(phase)) Runtime.getRuntime().halt(73);
            });
    String code;
    long version = 0;
    try {
      FilePort port =
          mode.equals("crash") || mode.equals("rebind")
              ? store.bind("tenant-r6", task, id, () -> {})
              : store.resume("tenant-r6", task, id, () -> {});
      if (mode.equals("changed-read")) port.read("crash.md", 1, 2000);
      else
        version =
            port.write(
                    mode.equals("changed-path") ? "other.md" : "crash.md",
                    mode.equals("changed-content") ? "changed arguments" : "after crash")
                .version();
      code = "SUCCESS";
    } catch (FileFailure error) {
      code = error.code();
    }
    Files.writeString(result, Payloads.json(Map.of("code", code, "version", version)));
    if (mode.equals("crash")) throw new AssertionError("Exit boundary not reached");
  }

  static void run(Path root, Path evidence) throws Exception {
    Files.createDirectories(evidence);
    try {
      for (String phase :
          List.of(
              "BINDING_BEFORE_RENAME",
              "BINDING_DURABLE",
              "STATE_BEFORE_RENAME",
              "WRITE_INTENT_DURABLE",
              "WRITE_COMMITTED")) {
        for (boolean compete : List.of(false, true)) scenario(root, evidence, phase, compete);
      }
      // A legacy workspace has no evidence of the original binding, including absent IDs.
      String task = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
      String scope = Payloads.hash("tenant-r6:" + task);
      String payload =
          Payloads.json(
              Map.of(
                  "format",
                  1,
                  "scope",
                  scope,
                  "files",
                  Map.of(),
                  "observed",
                  Map.of(),
                  "operations",
                  Map.of()));
      Path state = root.resolve(scope + ".json");
      String legacy = Payloads.json(Map.of("digest", Payloads.hash(payload), "payload", payload));
      Files.writeString(state, legacy);
      var store = new SecureWorkspaceStore(root);
      expect("FS_BINDING_UNPROVEN", () -> store.bind("tenant-r6", task, id, () -> {}));
      expect("FS_BINDING_UNPROVEN", () -> store.resume("tenant-r6", task, id, () -> {}));
      check(Files.readString(state).equals(legacy), "legacy bytes unchanged by refused recovery");
      Files.copy(state, evidence.resolve("legacy-state.json"));
    } finally {
      Files.writeString(evidence.resolve("assertions.json"), Payloads.json(ASSERTIONS));
      Files.writeString(evidence.resolve("processes.json"), Payloads.json(PROCESSES));
      Files.writeString(evidence.resolve("facts.json"), Payloads.json(FACTS));
    }
  }

  private static void scenario(Path root, Path evidence, String phase, boolean compete)
      throws Exception {
    Path folder = evidence.resolve(phase + (compete ? "-compete" : "-alone"));
    Files.createDirectory(folder);
    String task = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
    var store = new SecureWorkspaceStore(root);
    var missing = fresh(store, task).read("crash.md", 1, 2000);
    check(!missing.exists() && missing.version() == 0, "initial missing observation");
    child(root, folder, "crash", task, id, phase, 73);
    copyState(root, folder.resolve("after-exit-state.json"), task);
    if (!phase.equals("BINDING_BEFORE_RENAME"))
      check(
          state(folder.resolve("after-exit-state.json"))
                  .path("bindings")
                  .path(id)
                  .path("crash.md")
                  .asLong(-1)
              == 0,
          "original missing binding persisted before invocation intent");
    boolean committed = phase.equals("WRITE_COMMITTED");
    boolean intent = phase.equals("WRITE_INTENT_DURABLE") || committed;
    long expectedVersion = committed ? 1 : 0;
    String expectedContent = committed ? "after crash" : "";
    if (compete) {
      // This advances the *global* observation. It must not replace the failed operation's CAS.
      var competing = fresh(store, task).write("crash.md", "independent newer content");
      expectedVersion++;
      expectedContent = "independent newer content";
      check(competing.version() == expectedVersion, "competing writer commits expected version");
    }
    copyState(root, folder.resolve("before-recovery-state.json"), task);
    JsonNode replay = child(root, folder, "resume", task, id, "none", 0);
    String expectedCode =
        !intent ? "FS_BINDING_UNPROVEN" : compete && !committed ? "FS_STALE_VERSION" : "SUCCESS";
    check(replay.path("code").asText().equals(expectedCode), "fresh JVM recovery " + expectedCode);
    if (expectedCode.equals("SUCCESS")) {
      check(replay.path("version").asLong() == 1, "recovery returns original first version");
      if (!compete) {
        expectedVersion = 1;
        expectedContent = "after crash";
      }
    }
    if (intent) {
      for (String mode : List.of("changed-content", "changed-path", "changed-read", "rebind")) {
        JsonNode changed = child(root, folder, mode, task, id, "none", 0);
        check(changed.path("code").asText().equals("FS_OPERATION_MISMATCH"), mode + " refused");
      }
    } else if (!phase.equals("BINDING_BEFORE_RENAME")) {
      JsonNode rebound = child(root, folder, "rebind", task, id, "none", 0);
      check(
          rebound.path("code").asText().equals("FS_OPERATION_MISMATCH"), "original rebind refused");
      for (String mode : List.of("changed-content", "changed-path", "changed-read")) {
        JsonNode changed = child(root, folder, mode, task, id, "none", 0);
        check(changed.path("code").asText().equals("FS_BINDING_UNPROVEN"), mode + " unproven");
      }
    }
    FilePort.Read current = fresh(store, task).read("crash.md", 1, 2000);
    check(current.version() == expectedVersion, "competing/current version retained");
    check(
        current.exists()
            ? current.sha256().equals(Payloads.hash(expectedContent))
                && current.content().equals("1: " + expectedContent + "\n")
            : expectedContent.isEmpty(),
        "competing/current bytes and digest retained");
    check(!fresh(store, task).read("other.md", 1, 2000).exists(), "changed path has no effect");
    copyState(root, folder.resolve("final-state.json"), task);
    JsonNode finalState = state(folder.resolve("final-state.json"));
    if (!phase.equals("BINDING_BEFORE_RENAME"))
      check(
          finalState.path("bindings").path(id).path("crash.md").asLong(-1) == 0,
          "original binding remains zero after competing reads and recovery");
    if (intent)
      check(
          finalState.path("operations").path(id).path("expected").asLong(-1) == 0,
          "durable CAS expectation unchanged");
    FACTS.add(
        Map.of(
            "phase",
            phase,
            "compete",
            compete,
            "taskId",
            task,
            "operationId",
            id,
            "recovery",
            replay,
            "current",
            current));
  }

  private static FilePort fresh(SecureWorkspaceStore store, String task) {
    return store.bind("tenant-r6", task, UUID.randomUUID().toString(), () -> {});
  }

  private static void copyState(Path root, Path result, String task) throws Exception {
    Files.copy(root.resolve(Payloads.hash("tenant-r6:" + task) + ".json"), result);
  }

  private static JsonNode state(Path path) throws Exception {
    var envelope = Payloads.read(Files.readString(path), JsonNode.class);
    return Payloads.read(envelope.path("payload").asText(), JsonNode.class);
  }

  private static JsonNode child(
      Path root, Path folder, String mode, String task, String id, String phase, int expectedExit)
      throws Exception {
    Path result = folder.resolve(mode + ".json");
    var command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx768m",
            "-cp",
            System.getProperty("java.class.path"),
            WorkspaceRecoveryScenario.class.getName(),
            root.toString(),
            result.toString(),
            mode,
            task,
            id,
            phase);
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(folder.resolve(mode + ".log").toFile())
            .start();
    try {
      check(process.waitFor(20, TimeUnit.SECONDS), mode + " JVM exits in foreground");
      check(process.exitValue() == expectedExit, mode + " JVM expected exit " + expectedExit);
      return expectedExit == 0 ? Payloads.read(Files.readString(result), JsonNode.class) : null;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
      PROCESSES.add(
          Map.of(
              "pid",
              process.pid(),
              "alive",
              process.isAlive(),
              "exit",
              process.exitValue(),
              "mode",
              mode,
              "phase",
              phase,
              "taskId",
              task));
    }
  }

  private static void expect(String code, Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected " + code);
    } catch (FileFailure error) {
      check(code.equals(error.code()), code);
    }
  }

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    ASSERTIONS.add(name);
    System.out.println("PASS R6 " + name);
  }
}
