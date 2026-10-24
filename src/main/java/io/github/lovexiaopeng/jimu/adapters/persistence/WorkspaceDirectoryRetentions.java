package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.Candidate;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.Workspace;
import java.util.function.Supplier;

/** 原安全工作区清单字节和POSIX验证不变；调用由Service放在数据库事务外。 */
final class WorkspaceDirectoryRetentions implements Workspace {
  private final Supplier<SecureWorkspaceStore> store;

  WorkspaceDirectoryRetentions(Supplier<SecureWorkspaceStore> store) {
    this.store = store;
  }

  public boolean available() {
    return store.get() != null;
  }

  public String plan(Candidate candidate) {
    return Payloads.json(store.get().planPurge(candidate.tenant(), candidate.taskId(), () -> {}));
  }

  public void purge(Candidate candidate, String receipt) {
    var plan = Payloads.read(receipt, SecureWorkspaceStore.PurgePlan.class);
    store.get().purge(candidate.tenant(), candidate.taskId(), plan, () -> {});
  }
}
