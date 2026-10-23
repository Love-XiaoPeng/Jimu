package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;
import java.util.Map;

/** One cumulative proof-read envelope, including authorization before asynchronous body input. */
public final class SessionScopeQueryBudget implements AutoCloseable {
  private static final ThreadLocal<SessionScopeQueryBudget> CURRENT = new ThreadLocal<>();
  private long reads, bytes, operations;
  private final Runnable deadline;

  public SessionScopeQueryBudget(SessionSearchScopeLedger.QueryResources prior, Runnable deadline) {
    this.deadline = deadline;
    reads = prior.reads();
    bytes = prior.bytes();
    operations = prior.operations();
    CURRENT.set(this);
  }

  public SessionSearchScopeLedger.QueryResources resources() {
    return new SessionSearchScopeLedger.QueryResources(reads, bytes, operations);
  }

  public static void operation(long count) {
    var budget = CURRENT.get();
    if (budget == null) return;
    long previous = budget.operations;
    budget.operations += count;
    if (budget.operations > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    if (previous / 1024 != budget.operations / 1024) budget.deadline.run();
  }

  public static void charge(List<Map<String, Object>> rows) {
    var budget = CURRENT.get();
    if (budget == null) return;
    budget.deadline.run();
    if (++budget.reads > 4096) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    long batch = 0;
    for (var row : rows) {
      operation(row.size() + 1L);
      for (var entry : row.entrySet())
        batch +=
            8L
                + SessionContracts.bytes(entry.getKey())
                + (entry.getValue() == null
                    ? 1
                    : SessionContracts.bytes(entry.getValue().toString()));
    }
    budget.bytes += batch;
    // A single bounded provider batch, its parse tree and transient serialized projection, plus
    // bounded scope/scan/ranking/checkpoint state. Proof bytes are cumulative, scratch is live.
    if (budget.bytes > 16777216 || batch * 3 + 1048576 > 16777216)
      throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
  }

  @Override
  public void close() {
    CURRENT.remove();
  }
}
