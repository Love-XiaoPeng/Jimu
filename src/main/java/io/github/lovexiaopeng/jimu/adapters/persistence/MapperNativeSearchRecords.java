package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.search.port.NativeSearchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** 同DataSource/UoW的原生检索单项Mapper与codec适配；不持有资格或循环。 */
final class MapperNativeSearchRecords implements NativeSearchRecords {
  private final JdbcLedger ledger;

  MapperNativeSearchRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  private record TaskView(Map<String, Object> row) implements Task {}

  private record BudgetView(Map<String, Object> row) implements Budget {
    public long storageHeld() {
      return ((Number) row.get("storage_held")).longValue();
    }

    public long mediaCheckpointHeld() {
      return ((Number) row.get("media_checkpoint_held")).longValue();
    }

    public long storageLimit() {
      return ((Number) row.get("storage_limit")).longValue();
    }
  }

  private record CallRowView(Map<String, Object> row) implements CallRow {
    public String state() {
      return (String) row.get("state");
    }

    public String tool() {
      return (String) row.get("tool");
    }

    public String effectIntentId() {
      return (String) row.get("effect_intent_id");
    }
  }

  private record AggregateView(Map<String, Object> row) implements Aggregate {
    public long roots() {
      return ((Number) row.get("roots")).longValue();
    }

    public long bytes() {
      return ((Number) row.get("bytes")).longValue();
    }
  }

  private record WorkerView(Map<String, Object> row) implements Worker {
    public String hostId() {
      return (String) row.get("host_id");
    }
  }

  private record RunRowView(Map<String, Object> row) implements RunRow {
    public String tenantId() {
      return (String) row.get("tenant_id");
    }

    public String taskId() {
      return (String) row.get("task_id");
    }

    public String rootInvocation() {
      return (String) row.get("root_invocation");
    }

    public String effectId() {
      return (String) row.get("effect_id");
    }

    public String attemptId() {
      return (String) row.get("attempt_id");
    }

    public String workerId() {
      return (String) row.get("worker_id");
    }

    public String runtimeDirectory() {
      return (String) row.get("runtime_directory");
    }

    public String snapshotDigest() {
      return (String) row.get("snapshot_digest");
    }

    public String phase() {
      return (String) row.get("phase");
    }

    public String processIdentity() {
      return (String) row.get("process_identity");
    }

    public Object processIdentityObject() {
      return row.get("process_identity");
    }

    public Object resultHashObject() {
      return row.get("result_hash");
    }

    public String cursorIndex() {
      return (String) row.get("cursor_index");
    }

    public String queryDigest() {
      return (String) row.get("query_digest");
    }

    public String toolPin() {
      return (String) row.get("tool_pin");
    }

    public String resultHash() {
      return (String) row.get("result_hash");
    }

    public long resultSize() {
      return ((Number) row.get("result_size")).longValue();
    }

    public int stopRequested() {
      return ((Number) row.get("stop_requested")).intValue();
    }
  }

  private record ReservationView(Map<String, Object> row) implements Reservation {
    public String state() {
      return (String) row.get("state");
    }

    public long amount() {
      return ((Number) row.get("amount")).longValue();
    }

    public long actual() {
      return ((Number) row.get("actual")).longValue();
    }

    public Object actualObject() {
      return row.get("actual");
    }
  }

  private static <T> List<T> lazy(
      List<Map<String, Object>> rows, Function<Map<String, Object>, T> wrap) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public T get(int i) {
        return wrap.apply(rows.get(i));
      }
    };
  }

  private record IndexView(com.fasterxml.jackson.databind.JsonNode node) implements CursorIndex {
    public boolean has(String cursor) {
      return node.has(cursor);
    }

    public CursorIndex path(String cursor) {
      return new IndexView(node.path(cursor));
    }

    public int intValue() {
      return node.intValue();
    }
  }

  public <T> T leaseTransaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public <T> T transaction(Supplier<T> work) {
    return ledger.tx(work);
  }

  public void fenced(Lease lease, boolean admission) {
    ledger.fenced(lease, admission);
  }

  public Budget budget(String tenant) {
    return new BudgetView(ledger.bucket(tenant));
  }

  public Task locked(String tenant, String task) {
    return new TaskView(ledger.locked(tenant, task));
  }

  public void settleStoppedWriters(Task task) {
    ledger.settleStoppedWriters(((TaskView) task).row());
  }

  public void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome) {
    ledger.reserve(tenant, task, reservation, dispatch, category, amount, state, outcome);
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public CursorIndex readIndex(String raw) {
    return new IndexView(Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
  }

  public NativeIdentity readIdentity(String raw) {
    return Payloads.read(raw, NativeIdentity.class);
  }

  public List<RunRow> selectNonStoppedSearch(String tenant, String task, String invocation) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                    .class)
            .selectNonStoppedSearch(ledger.queryContext(), tenant, task, invocation),
        RunRowView::new);
  }

  public CallRow selectSearchInvocation(String tenant, String invocation, String task) {
    return new CallRowView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper
                        .InvocationMapper.class)
                .selectSearchInvocation(ledger.queryContext(), tenant, invocation, task)));
  }

  public Aggregate selectSearchAggregate(String tenant, String task) {
    return new AggregateView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper
                        .NativeSearchRunMapper.class)
                .selectSearchAggregate(ledger.queryContext(), tenant, task)));
  }

  public Worker selectSearchWorkerHost(String worker) {
    return new WorkerView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.worker.mapper
                        .WorkerProcessMapper.class)
                .selectSearchWorkerHost(ledger.queryContext(), worker)));
  }

  public int insertSearchRun(
      String tenant,
      String invocation,
      String task,
      String effect,
      String attempt,
      String worker,
      String host,
      String directory,
      String snapshot,
      String digest,
      String pin) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                .class)
        .insertSearchRun(
            ledger.queryContext(),
            tenant,
            invocation,
            task,
            effect,
            attempt,
            worker,
            host,
            directory,
            snapshot,
            digest,
            pin);
  }

  public int holdSearchStorage(String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper.class)
        .holdSearchStorage(ledger.queryContext(), tenant);
  }

  public int startSearchRun(
      String identity, String tenant, String task, String invocation, String attempt) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                .class)
        .startSearchRun(ledger.queryContext(), identity, tenant, task, invocation, attempt);
  }

  public RunRow selectSearchStopRun(
      String tenant, String invocation, String task, String effect, String worker) {
    return new RunRowView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper
                        .NativeSearchRunMapper.class)
                .selectSearchStopRun(
                    ledger.queryContext(), tenant, invocation, task, effect, worker)));
  }

  public int insertSearchStop(String tenant, String invocation, String digest, String identity) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchStopMapper
                .class)
        .insertSearchStop(ledger.queryContext(), tenant, invocation, digest, identity);
  }

  public int stopSearchRun(String identity, String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                .class)
        .stopSearchRun(ledger.queryContext(), identity, tenant, invocation);
  }

  public RunRow selectSearchCompleteRun(
      String tenant, String task, String invocation, String attempt) {
    return new RunRowView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper
                        .NativeSearchRunMapper.class)
                .selectSearchCompleteRun(
                    ledger.queryContext(), tenant, task, invocation, attempt)));
  }

  public int completeSearchRun(
      String hash, long size, String cursors, String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                .class)
        .completeSearchRun(ledger.queryContext(), hash, size, cursors, tenant, invocation);
  }

  public int settleSearchStorage(long size, String tenant, String reservation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper.class)
        .settleSearchStorage(ledger.queryContext(), size, tenant, reservation);
  }

  public int releaseCompletedSearchStorage(long bytes, String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper.class)
        .releaseCompletedSearchStorage(ledger.queryContext(), bytes, tenant);
  }

  public List<RunRow> selectSearchPage(String tenant, String task) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                    .class)
            .selectSearchPage(ledger.queryContext(), tenant, task),
        RunRowView::new);
  }

  public List<RunRow> selectNativeSearches(String host) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                    .class)
            .selectNativeSearches(ledger.queryContext(), host),
        RunRowView::new);
  }

  public List<RunRow> selectExpiredSearches(String host) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                    .class)
            .selectExpiredSearches(ledger.queryContext(), host),
        RunRowView::new);
  }

  public RunRow selectSearchRemovalRun(
      String tenant, String invocation, String task, String effect) {
    return new RunRowView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper
                        .NativeSearchRunMapper.class)
                .selectSearchRemovalRun(ledger.queryContext(), tenant, invocation, task, effect)));
  }

  public Reservation selectSearchRemovalReservation(String tenant, String reservation) {
    return new ReservationView(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper
                        .class)
                .selectSearchRemovalReservation(ledger.queryContext(), tenant, reservation)));
  }

  public int releaseRemovedSearchStorage(long bytes, String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper.class)
        .releaseRemovedSearchStorage(ledger.queryContext(), bytes, tenant);
  }

  public int releaseSearchReservation(String tenant, String reservation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper.class)
        .releaseSearchReservation(ledger.queryContext(), tenant, reservation);
  }

  public int removeSearchStorage(String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper.NativeSearchRunMapper
                .class)
        .removeSearchStorage(ledger.queryContext(), tenant, invocation);
  }
}
