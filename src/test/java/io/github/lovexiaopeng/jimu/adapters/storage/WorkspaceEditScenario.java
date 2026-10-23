package io.github.lovexiaopeng.jimu.adapters.storage;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Independent POSIX JVMs and hard exits at the actual file transaction boundaries. */
public final class WorkspaceEditScenario {
  private static final String TASK = "10000000-0000-0000-0000-000000000043";

  private static FilePort port(SecureWorkspaceStore store) {
    return store.bind("p4", TASK, UUID.randomUUID().toString(), () -> {});
  }

  private static void put(SecureWorkspaceStore store, String path, String value) {
    port(store).read(path, 1, 2000);
    port(store).write(path, value);
  }

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }

  private static void code(String expected, Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected " + expected);
    } catch (FileFailure error) {
      check(error.code().equals(expected), expected);
    }
  }

  private static Process child(String[] args, String mode, String path, String op, String detail)
      throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dloader.path=" + args[4],
                "-Dloader.main=" + WorkspaceEditScenario.class.getName(),
                "-cp",
                args[3],
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
    command.addAll(List.of(args));
    command.addAll(List.of(mode, path, op, detail));
    return new ProcessBuilder(command).inheritIO().start();
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    String selected = args[2];
    if (args.length > 5) {
      String mode = args[5], path = args[6], op = args[7], detail = args[8];
      final int[] states = {0};
      var store =
          new SecureWorkspaceStore(
              root,
              name -> {
                if (name.equals("STATE_BEFORE_RENAME")) states[0]++;
                if (mode.equals("crash")
                    && (name.equals(detail)
                        || detail.equals("COMMIT_BEFORE_RENAME")
                            && name.equals("STATE_BEFORE_RENAME")
                            && states[0] == 2)) Runtime.getRuntime().halt(73);
              });
      var edit = (EditPort) store.bind("p4", TASK, op, () -> {});
      if (mode.equals("race")) {
        Path ready = Path.of(detail + ".ready");
        Files.writeString(ready, "ready");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(Path.of(detail))) {
          if (System.nanoTime() > deadline) throw new AssertionError("Race gate deadline");
          Thread.sleep(10);
        }
      }
      try {
        edit.edit(path, "old", "new-" + op, false);
      } catch (FileFailure error) {
        if (mode.equals("race") && error.code().equals("FS_STALE_VERSION")) System.exit(74);
        throw error;
      }
      return;
    }
    var store = new SecureWorkspaceStore(root);
    store.initialize("p4", TASK, true, () -> {});
    if (selected.equals("E01")) {
      put(store, "literal", "aaa $1.*\\x");
      ((EditPort) port(store)).edit("literal", "aa", "!", false);
      check(
          store.inspect("p4", TASK, "literal", () -> {}).content().equals("!a $1.*\\x"),
          "literal non-overlap and replacement metacharacters");
      ((EditPort) port(store)).edit("literal", "$1.*\\x", "", false);
      check(
          store.inspect("p4", TASK, "literal", () -> {}).content().equals("!a "),
          "literal deletion exact bytes");
      put(store, "many", "aaaa");
      code("FS_AMBIGUOUS_EDIT", () -> ((EditPort) port(store)).edit("many", "aa", "b", false));
      code("FS_EDIT_NOT_FOUND", () -> ((EditPort) port(store)).edit("many", "z", "b", false));
      check(
          store.inspect("p4", TASK, "many", () -> {}).version() == 1,
          "failed matches preserve version");
      ((EditPort) port(store)).edit("many", "aa", "b", true);
      check(
          store.inspect("p4", TASK, "many", () -> {}).content().equals("bb"),
          "replace-all non-overlap oracle");
    } else if (selected.equals("E02")) {
      put(store, "unicode", "\uFEFF中文🙂e\u0301\r\nalpha\r\nbeta\nsolo\r");
      ((EditPort) port(store)).edit("unicode", "中文🙂e\u0301\nalpha", "修订🙂\nnext", false);
      check(
          store
              .inspect("p4", TASK, "unicode", () -> {})
              .content()
              .equals("修订🙂\r\nnext\r\nbeta\r\nsolo\r"),
          "UTF8 BOM combining characters CRLF oracle");
      put(store, "same", "a\r\nb");
      ((EditPort) port(store)).edit("same", "a\r\nb", "a\nb", false);
      check(
          store.inspect("p4", TASK, "same", () -> {}).version() == 2,
          "normalized equality still increments once");
      code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "", "x", false));
      code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "x", "x", false));
      code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "a", "\uD800", false));
      ((EditPort) port(store)).edit("same", "a", "\0", false);
      code("FS_NOT_TEXT", () -> ((EditPort) port(store)).edit("same", "b", "c", false));
      check(
          port(store).read("same", 1, 2000).content().contains("\0"),
          "NUL written text remains readable");
      put(store, "empty", "");
      code("FS_EDIT_NOT_FOUND", () -> ((EditPort) port(store)).edit("empty", "a", "b", false));
    } else if (selected.equals("E03")) {
      put(store, "race", "old");
      Path evidence = Path.of(args[1]);
      Path gateA = evidence.resolve("gate-a"), gateB = evidence.resolve("gate-b");
      Process a = child(args, "race", "race", UUID.randomUUID().toString(), gateA.toString());
      Process b = child(args, "race", "race", UUID.randomUUID().toString(), gateB.toString());
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(Path.of(gateA + ".ready"))
            || !Files.exists(Path.of(gateB + ".ready"))) {
          if (System.nanoTime() > deadline)
            throw new AssertionError("Both JVM bindings must be durable");
          Thread.sleep(10);
        }
        Files.writeString(gateA, "go");
        Files.writeString(gateB, "go");
        check(
            a.waitFor(15, TimeUnit.SECONDS) && b.waitFor(15, TimeUnit.SECONDS),
            "both actual CAS JVMs exited");
        check(
            List.of(a.exitValue(), b.exitValue()).stream().sorted().toList().equals(List.of(0, 74)),
            "only one JVM CAS wins");
        check(
            store.inspect("p4", TASK, "race", () -> {}).version() == 2,
            "one content version increment");
      } finally {
        if (a.isAlive()) {
          a.destroyForcibly();
          a.waitFor();
        }
        if (b.isAlive()) {
          b.destroyForcibly();
          b.waitFor();
        }
      }
      put(store, "aba", "old");
      var stale = (EditPort) port(store);
      put(store, "aba", "middle");
      put(store, "aba", "old");
      code("FS_STALE_VERSION", () -> stale.edit("aba", "old", "replacement", false));
    } else if (selected.equals("E04")) {
      for (String phase :
          List.of(
              "BINDING_BEFORE_RENAME",
              "BINDING_DURABLE",
              "STATE_BEFORE_RENAME",
              "EDIT_INTENT_DURABLE",
              "COMMIT_BEFORE_RENAME",
              "EDIT_COMMITTED")) {
        String name = "crash-" + phase, op = UUID.randomUUID().toString();
        put(store, name, "old");
        Process crash = child(args, "crash", name, op, phase);
        check(
            crash.waitFor(15, TimeUnit.SECONDS) && crash.exitValue() == 73,
            "actual hard exit at " + phase);
        if (List.of("BINDING_BEFORE_RENAME", "BINDING_DURABLE", "STATE_BEFORE_RENAME")
            .contains(phase)) {
          code("FS_BINDING_UNPROVEN", () -> store.resume("p4", TASK, op, () -> {}));
          check(
              store.inspect("p4", TASK, name, () -> {}).content().equals("old"),
              "unproven binding never writes");
        } else {
          var edit = (EditPort) store.resume("p4", TASK, op, () -> {});
          var receipt = edit.edit(name, "old", "new-" + op, false);
          check(receipt.version() == 2, "crash recovery performs at most one write");
          put(store, name, "later");
          check(
              ((EditPort) store.resume("p4", TASK, op, () -> {}))
                  .edit(name, "old", "new-" + op, false)
                  .equals(receipt),
              "original receipt survives later content");
          check(
              store.inspect("p4", TASK, name, () -> {}).content().equals("later"),
              "recovery does not overwrite later content");
          code("FS_OPERATION_MISMATCH", () -> edit.edit(name, "old", "different", false));
        }
      }
      put(store, "frozen", "old");
      String op = UUID.randomUUID().toString();
      Process crash = child(args, "crash", "frozen", op, "EDIT_INTENT_DURABLE");
      check(
          crash.waitFor(15, TimeUnit.SECONDS) && crash.exitValue() == 73,
          "frozen intent process exited");
      put(store, "frozen", "later");
      code(
          "FS_STALE_VERSION",
          () ->
              ((EditPort) store.resume("p4", TASK, op, () -> {}))
                  .edit("frozen", "old", "new-" + op, false));
    } else throw new IllegalArgumentException("Unknown edit scenario");
    Files.writeString(
        Path.of(args[1]).resolve(selected + ".json"),
        Payloads.json(store.searchSnapshot("p4", TASK, () -> {})));
  }
}
