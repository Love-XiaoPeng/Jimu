package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Private evaluation fixture: real host FilePort receipts, never direct workspace-file edits. */
public final class EvaluationWorkspaceMain {
  private EvaluationWorkspaceMain() {}

  public static void main(String[] args) throws Exception {
    var store = new SecureWorkspaceStore(Path.of(args[0]));
    store
        .bind(args[1], args[2], UUID.randomUUID().toString(), () -> {})
        .read("reports/w4.md", 1, 2000);
    var receipt =
        store
            .bind(args[1], args[2], UUID.randomUUID().toString(), () -> {})
            .write("reports/w4.md", Files.readString(Path.of(args[3])));
    System.out.println(Payloads.json(receipt));
  }
}
