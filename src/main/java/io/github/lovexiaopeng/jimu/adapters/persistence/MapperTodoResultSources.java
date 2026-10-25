package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.execution.entity.TaskAttemptReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptReadMapper;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoResultSources;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Original map member semantics and same-DS/UoW single-column attempt query. */
final class MapperTodoResultSources implements TodoResultSources {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;
  private final Map<String, Object> invocation;

  MapperTodoResultSources(
      JdbcLedger ledger, Map<String, Object> task, Map<String, Object> invocation) {
    this.ledger = ledger;
    this.task = task;
    this.invocation = invocation;
  }

  public boolean appliedRevisionPresent() {
    return invocation.get("todo_applied_revision") != null;
  }

  public long appliedRevision() {
    return n(invocation, "todo_applied_revision");
  }

  public long expectedRevision() {
    return n(invocation, "todo_expected_revision");
  }

  public String state() {
    return s(invocation, "state");
  }

  public String tool() {
    return s(invocation, "tool");
  }

  public boolean committedAttemptPresent() {
    return invocation.get("todo_committed_attempt_id") != null;
  }

  public boolean committedFencePresent() {
    return invocation.get("todo_committed_fence") != null;
  }

  public boolean eventSequencePresent() {
    return invocation.get("todo_event_seq") != null;
  }

  public boolean committedAtPresent() {
    return invocation.get("todo_committed_at") != null;
  }

  public List<?> attempts() {
    String tenant = s(task, "tenant_id");
    String taskId = s(task, "id");
    String attempt = s(invocation, "todo_committed_attempt_id");
    long fence = n(invocation, "todo_committed_fence");
    var wrapper = new QueryWrapper<TaskAttemptReadEntity>();
    wrapper
        .select("id")
        .eq("tenant_id", tenant)
        .eq("task_id", taskId)
        .eq("id", attempt)
        .eq("fence", fence);
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger
        .mappers
        .session()
        .selectList(TaskAttemptReadMapper.class.getName() + ".selectMaps", parameters);
  }

  public String contentState() {
    return s(invocation, "content_state");
  }

  public String outputSnapshot() {
    return s(invocation, "output_snapshot");
  }

  public String hash(String snapshot) {
    return Payloads.hash(snapshot);
  }

  public String outputDigest() {
    return s(invocation, "output_digest");
  }
}
