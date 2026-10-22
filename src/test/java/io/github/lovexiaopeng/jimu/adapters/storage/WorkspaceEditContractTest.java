package io.github.lovexiaopeng.jimu.adapters.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceEditContractTest {
  @TempDir Path root;
  private final String task = UUID.randomUUID().toString();

  private SecureWorkspaceStore store(boolean format3) throws Exception {
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    var store =
        new SecureWorkspaceStore(
            root, name -> {}, Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
    store.initialize("test", task, format3, () -> {});
    return store;
  }

  private FilePort port(SecureWorkspaceStore store) {
    return store.bind("test", task, UUID.randomUUID().toString(), () -> {});
  }

  private void put(SecureWorkspaceStore store, String path, String text) {
    port(store).read(path, 1, 2000);
    port(store).write(path, text);
  }

  private String text(SecureWorkspaceStore store, String path) {
    return store.inspect("test", task, path, () -> {}).content();
  }

  private void code(String code, Runnable action) {
    assertEquals(code, assertThrows(FileFailure.class, action::run).code());
  }

  @Test
  void literalNonoverlapDeletionAndAmbiguity() throws Exception {
    var store = store(true);
    put(store, "a", "aaa $1.*\\x");
    var before = store.searchSnapshot("test", task, () -> {}).files().get("a");
    ((EditPort) port(store)).edit("a", "aa", "!", false);
    assertEquals("!a $1.*\\x", text(store, "a"));
    var after = store.searchSnapshot("test", task, () -> {}).files().get("a");
    assertEquals(before.modifiedAt() + 1, after.modifiedAt());
    ((EditPort) port(store)).edit("a", "$1.*\\x", "", false);
    assertEquals("!a ", text(store, "a"));
    put(store, "many", "aaaa");
    code("FS_AMBIGUOUS_EDIT", () -> ((EditPort) port(store)).edit("many", "aa", "b", false));
    assertEquals("aaaa", text(store, "many"));
    ((EditPort) port(store)).edit("many", "aa", "b", true);
    assertEquals("bb", text(store, "many"));
    code("FS_EDIT_NOT_FOUND", () -> ((EditPort) port(store)).edit("many", "z", "b", false));
    assertEquals("bb", text(store, "many"));
  }

  @Test
  void unicodeBomNewlineAndRawEqualityOracle() throws Exception {
    var store = store(true);
    put(store, "u", "\uFEFF中文🙂e\u0301\r\nalpha\r\nbeta\nsolo\r");
    ((EditPort) port(store)).edit("u", "中文🙂e\u0301\nalpha", "修订🙂\nnext", false);
    assertEquals("修订🙂\r\nnext\r\nbeta\r\nsolo\r", text(store, "u"));
    put(store, "same", "a\r\nb");
    var version = store.inspect("test", task, "same", () -> {}).version();
    ((EditPort) port(store)).edit("same", "a\r\nb", "a\nb", false);
    assertEquals("a\r\nb", text(store, "same"));
    assertEquals(version + 1, store.inspect("test", task, "same", () -> {}).version());
    code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "", "x", false));
    code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "x", "x", false));
    code("INPUT_INVALID", () -> ((EditPort) port(store)).edit("same", "a", "\uD800", false));
    ((EditPort) port(store)).edit("same", "a", "\0", false);
    code("FS_NOT_TEXT", () -> ((EditPort) port(store)).edit("same", "b", "c", false));
  }

  @Test
  void observedCasAndStableOperationReplay() throws Exception {
    var store = store(true);
    code("FS_NOT_OBSERVED", () -> ((EditPort) port(store)).edit("a", "x", "y", false));
    port(store).read("a", 1, 2000);
    code("FS_NOT_FOUND", () -> ((EditPort) port(store)).edit("a", "x", "y", false));
    put(store, "a", "x");
    String op = UUID.randomUUID().toString();
    var edit = (EditPort) store.bind("test", task, op, () -> {});
    var receipt = edit.edit("a", "x", "y", false);
    put(store, "a", "z");
    assertEquals(
        receipt, ((EditPort) store.resume("test", task, op, () -> {})).edit("a", "x", "y", false));
    assertEquals("z", text(store, "a"));
    code("FS_OPERATION_MISMATCH", () -> edit.edit("a", "x", "v", false));
    var stale = (EditPort) port(store);
    put(store, "a", "different");
    code("FS_STALE_VERSION", () -> stale.edit("a", "missing", "value", false));
  }

  @Test
  void oldFormatPreservesCodecAndPinsRejectWrongFormat() throws Exception {
    var store = store(false);
    put(store, "a", "legacy");
    Path state = root.resolve(Payloads.hash("test:" + task) + ".json");
    byte[] before = Files.readAllBytes(state);
    store.initialize("test", task, false, () -> {});
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(state)));
    assertTrue(!new String(before, java.nio.charset.StandardCharsets.UTF_8).contains("modifiedAt"));
    code("FS_BINDING_UNPROVEN", () -> store.initialize("test", task, true, () -> {}));
    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(state)));
  }
}
