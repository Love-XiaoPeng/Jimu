package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.time;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.entity.ArtifactViewEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactViewMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskVisibilityEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskOperationsMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskVisibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactState;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionContentState;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords;
import java.util.List;
import java.util.Map;

/** 只转换类型化事实及执行具名MP/XML，不复制查询/取消/事件业务分支。 */
final class MapperTaskOperations implements TaskOperationsRecords {
  private final JdbcLedger ledger;
  private final TaskOperationsMapper sql;

  MapperTaskOperations(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(TaskOperationsMapper.class);
  }

  private List<Map<String, Object>> select(Class<?> mapper, Object wrapper) {
    var parameters = new java.util.HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    return ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", parameters);
  }

  public boolean scopeHint(Tasks.Identity identity, String taskId) {
    var wrapper = new LambdaQueryWrapper<TaskVisibilityEntity>();
    wrapper
        .select(TaskVisibilityEntity::getCapabilities)
        .eq(TaskVisibilityEntity::getTenantId, identity.tenant())
        .eq(TaskVisibilityEntity::getTaskId, taskId)
        .eq(TaskVisibilityEntity::getOwnerActorId, identity.actor());
    var found = select(TaskVisibilityMapper.class, wrapper);
    return found.size() == 1 && JdbcSessionScopes.task(found.getFirst());
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public void lockScope(String tenant, String task) {
    ledger.sessionScopes.lock(tenant, task);
  }

  public Row visible(Tasks.Identity identity, String task) {
    return row(ledger.visible(identity, task));
  }

  public Row lock(String tenant, String task) {
    return row(ledger.locked(tenant, task));
  }

  Row row(Map<String, Object> value) {
    return new Record(value);
  }

  private final class Record implements Row {
    private final Map<String, Object> value;
    private final String tenant, id;

    Record(Map<String, Object> value) {
      this.value = value;
      tenant = s(value, "tenant_id");
      id = s(value, "id");
    }

    public Snapshot snapshot() {
      return new Snapshot(
          id,
          s(value, "state"),
          n(value, "revision"),
          time(value, "created_at"),
          time(value, "updated_at"),
          time(value, "cancel_requested_at"),
          s(value, "code"),
          s(value, "summary"),
          n(value, "next_event_seq"),
          JdbcSessionScopes.task(value),
          JdbcSessionRecords.task(value),
          ledger.questionTask(value));
    }

    public void requireAccess() {
      ledger.access(value);
    }

    public List<Tasks.Artifact> artifacts() {
      var wrapper = new LambdaQueryWrapper<ArtifactViewEntity>();
      wrapper
          .eq(ArtifactViewEntity::getTenantId, tenant)
          .eq(ArtifactViewEntity::getTaskId, id)
          .eq(ArtifactViewEntity::getState, ArtifactState.PUBLISHED)
          .orderByAsc(ArtifactViewEntity::getRowId);
      return select(ArtifactViewMapper.class, wrapper).stream()
          .map(
              r ->
                  new Tasks.Artifact(
                      s(r, "id"),
                      s(r, "media_type"),
                      n(r, "size"),
                      s(r, "sha256"),
                      time(r, "expires_at")))
          .toList();
    }

    public boolean historyRetained() {
      var h = ledger.sessions.history(value, false);
      return n(h, "retained") == 1 && SessionContentState.PRESENT.equals(s(h, "content_state"));
    }

    public boolean writersQuiescent() {
      return ledger.writersQuiescent(value);
    }

    public void requestCancel(boolean changeState) {
      if (changeState) sql.requestCancel(ledger.queryContext(), tenant, id);
      else sql.requestCancelIntent(ledger.queryContext(), tenant, id);
    }

    public void event(String fact, String type, String code) {
      ledger.event(value, fact, type, code);
    }

    public Row reload() {
      return lock(tenant, id);
    }

    public long oldestEvent() {
      return sql.oldestEvent(ledger.queryContext(), tenant, id);
    }

    public List<Tasks.Event> events(long after, int limit) {
      return sql.eventPage(ledger.queryContext(), tenant, id, after, limit).stream()
          .map(
              e ->
                  new Tasks.Event(
                      s(e, "event_id"),
                      n(e, "seq"),
                      s(e, "type"),
                      id,
                      time(e, "occurred_at"),
                      s(e, "code"),
                      s(e, "status"),
                      e.get("artifact_id") == null ? List.of() : List.of(s(e, "artifact_id"))))
          .toList();
    }

    public List<Question> lockQuestions() {
      return sql.lockQuestions(ledger.queryContext(), tenant, id).stream()
          .map(q -> new Question(s(q, "id"), s(q, "invocation_id")))
          .toList();
    }

    public void closeQuestion(Question q, String code, String state) {
      sql.closeQuestion(ledger.queryContext(), code, tenant, q.id());
      sql.stopQuestionInvocation(ledger.queryContext(), state, tenant, q.invocationId());
      sql.settleQuestion(ledger.queryContext(), tenant, q.invocationId());
    }

    public void discardQuestionDelivery(String code) {
      sql.discardQuestionDelivery(ledger.queryContext(), code, tenant, id);
    }

    public void clearQuestionContinuation() {
      sql.clearQuestionContinuation(ledger.queryContext(), tenant, id);
    }

    public void setTerminal(String state, String code) {
      sql.setTerminal(ledger.queryContext(), state, code, tenant, id);
    }

    public void markUnknownTools() {
      sql.markUnknownInvocations(ledger.queryContext(), tenant, id);
      sql.holdUnknownTools(ledger.queryContext(), tenant, id);
    }

    public void releaseQueue() {
      reservation(id + ":queue", "QUEUE");
      int count = sql.releaseReservation(ledger.queryContext(), tenant, id + ":queue");
      sql.releaseQueue(ledger.queryContext(), count, tenant);
    }

    public void releaseRun() {
      reservation(id + ":run", "RUNNING");
      if (sql.releaseReservation(ledger.queryContext(), tenant, id + ":run") == 1)
        sql.releaseRun(ledger.queryContext(), tenant);
    }

    public void releaseQuestionTask() {
      if (ledger.questionTask(value)) reservation(id + ":question-task", "QUESTION_TASK");
      if (ledger.questionTask(value)
          && sql.releaseReservation(ledger.queryContext(), tenant, id + ":question-task") == 1)
        sql.releaseQuestionTask(ledger.queryContext(), tenant);
    }

    public void holdUnknownModels() {
      sql.holdUnknownModels(ledger.queryContext(), tenant, id);
    }

    public void stopAttempt(String state) {
      ledger.progressAttempt(value);
      sql.stopAttempt(ledger.queryContext(), state, tenant, s(value, "current_attempt_id"));
    }

    private void reservation(String reservation, String category) {
      if (ledger.queryContext() != null && ledger.queryContext().lineage())
        ledger
            .mappers
            .reservations()
            .lockProgressReservation(ledger.queryContext(), tenant, id, reservation, category);
    }
  }
}
