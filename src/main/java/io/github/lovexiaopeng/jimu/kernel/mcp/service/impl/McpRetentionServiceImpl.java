package io.github.lovexiaopeng.jimu.kernel.mcp.service.impl;

import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRetentions;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.McpRetentionService;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.List;

/** 保留原writer/终态/UNKNOWN顺序；正文、游标、占用和事件同事务原子完成。 */
public final class McpRetentionServiceImpl implements McpRetentionService {
  private final McpRetentions records;
  private final LedgerUnitOfWork work;

  public McpRetentionServiceImpl(McpRetentions records, LedgerUnitOfWork work) {
    this.records = records;
    this.work = work;
  }

  @Override
  public void cleanup() {
    for (var candidate : records.candidates()) {
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            var task = records.lockTask(candidate);
            if (!task.writersQuiescent()
                || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.state())
                || records.hasUnknownHold(candidate)) return null;
            for (var hold : task.lockExpiredHolds()) {
              String root = hold.id().substring(4);
              records.deleteCursors(candidate, root);
              records.purgeResult(candidate, root);
              records.releaseStorage(
                  candidate.tenant(), hold.actual() == null ? hold.amount() : hold.actual());
              records.releaseHold(candidate, hold.id());
              task.event("mcp-cleanup:" + root, "mcp.result_deleted");
            }
            return null;
          });
    }
  }
}
