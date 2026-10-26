package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoRecords;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;

/** 同DS/UoW的单项Mapper适配，构造仅存引用。 */
final class MapperTodoRecords implements TodoRecords {
  private final JdbcLedger ledger;

  MapperTodoRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  static Row wrap(Map<String, Object> row) {
    return new RowView(row);
  }

  static Map<String, Object> unwrap(Row row) {
    return ((RowView) row).row();
  }

  private record RowView(Map<String, Object> row) implements Row {
    public String tool() {
      return (String) row.get("tool");
    }

    public String providerId() {
      return (String) row.get("provider_id");
    }

    public String inputSnapshot() {
      return (String) row.get("input_snapshot");
    }

    public String inputDigest() {
      return (String) row.get("input_digest");
    }

    public String stepTask() {
      return (String) row.get("step_task");
    }

    public String stepState() {
      return (String) row.get("step_state");
    }

    public String responseSnapshot() {
      return (String) row.get("response_snapshot");
    }

    public String responseDigest() {
      return (String) row.get("response_digest");
    }

    public long recordVersion() {
      return ((Number) row.get("record_version")).longValue();
    }

    public long stepVersion() {
      return ((Number) row.get("step_version")).longValue();
    }

    public String pluginVersion() {
      return (String) row.get("plugin_version");
    }

    public String pluginDigest() {
      return (String) row.get("plugin_digest");
    }

    public long installationEpoch() {
      return ((Number) row.get("installation_epoch")).longValue();
    }

    public long todoExpectedRevision() {
      return ((Number) row.get("todo_expected_revision")).longValue();
    }

    public String id() {
      return (String) row.get("id");
    }

    public String state() {
      return (String) row.get("state");
    }

    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }

    public long revision() {
      return ((Number) row.get("revision")).longValue();
    }

    public long toolsUsed() {
      return ((Number) row.get("tools_used")).longValue();
    }

    public long maxTools() {
      return ((Number) row.get("max_tools")).longValue();
    }

    public long toolUsed() {
      return ((Number) row.get("tool_used")).longValue();
    }

    public long toolLimit() {
      return ((Number) row.get("tool_limit")).longValue();
    }

    public long nextEventSeq() {
      return ((Number) row.get("next_event_seq")).longValue();
    }

    public String contentState() {
      return (String) row.get("content_state");
    }

    public java.time.Instant retainUntil() {
      return JdbcLedger.time(row, "retain_until");
    }

    public String invocationTask() {
      return (String) row.get("invocation_task");
    }

    public String sourceStepId() {
      return (String) row.get("source_step_id");
    }

    public String invocationStep() {
      return (String) row.get("invocation_step");
    }

    public long todoAppliedRevision() {
      return ((Number) row.get("todo_applied_revision")).longValue();
    }

    public String committedAttemptId() {
      return (String) row.get("committed_attempt_id");
    }

    public String todoCommittedAttemptId() {
      return (String) row.get("todo_committed_attempt_id");
    }

    public long committedFence() {
      return ((Number) row.get("committed_fence")).longValue();
    }

    public long todoCommittedFence() {
      return ((Number) row.get("todo_committed_fence")).longValue();
    }

    public long updatedSeq() {
      return ((Number) row.get("updated_seq")).longValue();
    }

    public long todoEventSeq() {
      return ((Number) row.get("todo_event_seq")).longValue();
    }

    public String outputDigest() {
      return (String) row.get("output_digest");
    }

    public String invocationDigest() {
      return (String) row.get("invocation_digest");
    }

    public long pendingCount() {
      return ((Number) row.get("pending_count")).longValue();
    }

    public long inProgressCount() {
      return ((Number) row.get("in_progress_count")).longValue();
    }

    public long completedCount() {
      return ((Number) row.get("completed_count")).longValue();
    }

    public String sourceInvocationId() {
      return (String) row.get("source_invocation_id");
    }

    public String attemptId() {
      return (String) row.get("attempt_id");
    }

    public java.time.Instant todoCommittedAt() {
      return JdbcLedger.time(row, "todo_committed_at");
    }

    public Object todoExpectedRevisionObject() {
      return row.get("todo_expected_revision");
    }

    public Object todoAppliedRevisionObject() {
      return row.get("todo_applied_revision");
    }

    public Object taskIdObject() {
      return row.get("task_id");
    }
  }

  private static List<Row> lazy(List<Map<String, Object>> rows) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public Row get(int i) {
        return wrap(rows.get(i));
      }
    };
  }

  private record CallView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall wire)
      implements WireCall {
    public String id() {
      return wire.id();
    }

    public String name() {
      return wire.name();
    }

    public String arguments() {
      return wire.arguments();
    }
  }

  private record ResponseView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response wire)
      implements Response {
    public int version() {
      return wire.version();
    }

    public List<WireCall> calls() {
      var calls = wire.calls();
      return new AbstractList<>() {
        public int size() {
          return calls.size();
        }

        public WireCall get(int i) {
          return new CallView(calls.get(i));
        }
      };
    }
  }

  public <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public <T> T transaction(java.util.function.Supplier<T> work) {
    return ledger.tx(work);
  }

  public <T> T session(Lease lease, java.util.function.Supplier<T> work) {
    return ledger.todoSession(lease, work);
  }

  public Row fenced(Lease lease, boolean admission) {
    return wrap(ledger.fenced(lease, admission));
  }

  public boolean todoTask(Row task) {
    return ledger.todoTask(unwrap(task));
  }

  public Row todoProjection(Row task) {
    return wrap(ledger.todoProjection(unwrap(task)));
  }

  public void todoResultSource(Row task, Row invocation) {
    ledger.todoResultSource(unwrap(task), unwrap(invocation));
  }

  public void todoCommitFence(Lease lease) {
    ledger.todoCommitFence(lease);
  }

  public void todoStorage(Row task, long bytes) {
    ledger.todoStorage(unwrap(task), bytes);
  }

  public Row bucket(String tenant) {
    return wrap(ledger.bucket(tenant));
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

  public void event(Row task, String fact, String type, String body) {
    ledger.event(unwrap(task), fact, type, body);
  }

  public Long factSequence(String tenant, String task, String fact) {
    return ledger
        .mappers
        .events()
        .selectFactSequenceValue(ledger.queryContext(), tenant, task, fact);
  }

  public Row visible(Identity identity, String task) {
    return wrap(ledger.visible(identity, task));
  }

  public void access(Row task) {
    ledger.access(unwrap(task));
  }

  public ToolSnapshot tools(Row task) {
    return ledger.tools(unwrap(task));
  }

  public Response response(String raw) {
    return new ResponseView(
        Payloads.read(
            raw, io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response.class));
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public int bytes(String value) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(value);
  }

  public TodoPort.Result input(String raw) {
    return ledger.todos.input(raw);
  }

  public TodoPort.Result output(String raw) {
    return ledger.todos.output(raw);
  }

  public void view(Map<String, Object> value) {
    ledger.todos.view(value);
  }

  public List<Row> todoCandidates(String tenant, String task, String step, String slot) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper
                    .TodoInvocationReadMapper.class)
            .todoCandidates(ledger.queryContext(), tenant, task, step, slot));
  }

  public int countTodoTool(String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class)
        .countTodoTool(ledger.queryContext(), tenant, task);
  }

  public int countTodoBudget(String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper.class)
        .countTodoBudget(ledger.queryContext(), tenant);
  }

  public int settleTodoReservation(String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper.class)
        .settleTodoReservation(ledger.queryContext(), tenant, invocation);
  }

  public int completeTodoInvocation(
      String output,
      String digest,
      long revision,
      String attempt,
      long fence,
      long seq,
      String tenant,
      String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoInvocationReadMapper
                .class)
        .completeTodoInvocation(
            ledger.queryContext(),
            output,
            digest,
            revision,
            attempt,
            fence,
            seq,
            tenant,
            invocation);
  }

  public int advanceTodoRevision(
      String invocation,
      String step,
      String attempt,
      long fence,
      long seq,
      int pending,
      int inProgress,
      int completed,
      String digest,
      String tenant,
      String task,
      long expected) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoProjectionReadMapper
                .class)
        .advanceTodoRevision(
            ledger.queryContext(),
            invocation,
            step,
            attempt,
            fence,
            seq,
            pending,
            inProgress,
            completed,
            digest,
            tenant,
            task,
            expected);
  }

  public Row todoOwnerView(String tenant, String task, String actor) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper
                        .TodoProjectionReadMapper.class)
                .todoOwnerView(ledger.queryContext(), tenant, task, actor)));
  }
}
