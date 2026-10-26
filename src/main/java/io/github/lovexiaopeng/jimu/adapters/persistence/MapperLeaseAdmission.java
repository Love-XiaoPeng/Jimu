package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission;
import java.util.Map;

/** 无额外SQL/锁/事务；只在原准入位置读取事实或调用既有模块外部能力。 */
final class MapperLeaseAdmission implements LeaseAdmission {
  private final JdbcLedger ledger;
  private final Map<String, Object> task, evaluation;

  MapperLeaseAdmission(
      JdbcLedger ledger, Map<String, Object> task, Map<String, Object> evaluation) {
    this.ledger = ledger;
    this.task = task;
    this.evaluation = evaluation;
  }

  public String modelRejection() {
    return ledger.modelRejection(task);
  }

  public String runRejection() {
    return ledger.runRejection(evaluation);
  }

  public void requireScope() {
    ledger.requireScope(ledger.s(task, "tenant_id"), evaluation, task, null);
  }

  public void checkMaterials() {
    ledger.checkMaterials(task);
  }

  public void access() {
    ledger.access(task);
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public boolean directory() {
    return ledger.directoryTask(task);
  }

  public boolean todo() {
    return ledger.todoTask(task);
  }

  public boolean question() {
    return ledger.questionTask(task);
  }

  public String owner() {
    return ledger.s(task, "owner_actor_id");
  }

  public void sessionAdmission() {
    ledger.sessionAdmission(task);
  }

  public void directoryAdmission() {
    ledger.directoryAdmission(task);
  }

  public boolean todosEnabled() {
    return ledger.todosEnabled;
  }

  public String todoContentState() {
    return ledger.s(ledger.todoProjection(task), "content_state");
  }

  public void requireTodoModel() {
    ledger.models.requireTodo(ledger.snapshot(task));
  }

  public boolean questionsEnabled() {
    return ledger.questionsEnabled;
  }

  public boolean cancelled() {
    return task.get("cancel_requested_at") != null;
  }

  public boolean withinDeadline() {
    return ledger.n(task, "within_deadline") == 1;
  }
}
