package io.github.lovexiaopeng.jimu.kernel.mcp.port;

import java.util.List;

/** 内部维护从权威候选派生tenant/task；不提供新的公共访问或支付入口。 */
public interface McpRetentions {
  record Candidate(String tenant, String taskId) {}

  record Hold(String id, long amount, Long actual) {}

  interface LockedTask {
    String state();

    boolean writersQuiescent();

    List<Hold> lockExpiredHolds();

    void event(String fact, String type);
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  boolean hasUnknownHold(Candidate candidate);

  void deleteCursors(Candidate candidate, String rootInvocation);

  void purgeResult(Candidate candidate, String rootInvocation);

  void releaseStorage(String tenant, long bytes);

  void releaseHold(Candidate candidate, String id);
}
