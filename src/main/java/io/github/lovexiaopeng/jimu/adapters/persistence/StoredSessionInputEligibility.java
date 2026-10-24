package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionInputEligibility;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import java.util.List;
import java.util.Map;

/** 原codec及同DS/UoW的真实MP资格链；构造时不读取任务、输入或资格。 */
final class StoredSessionInputEligibility implements SessionInputEligibility {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final Call call;

  StoredSessionInputEligibility(JdbcSessionRecords records, Map<String, Object> task, Call call) {
    this.records = records;
    this.task = task;
    this.call = call;
  }

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public boolean searchTask() {
    return JdbcSessionRecords.searchTask(task);
  }

  public boolean traceTask() {
    return JdbcSessionRecords.traceTask(task);
  }

  public String tool() {
    return call.tool();
  }

  public String callerId() {
    return s(task, "id");
  }

  public List<String> scopeSearchTargets() {
    return records.scopes.contracts.input(call.input()).sessionIds();
  }

  public String scopeReadTarget() {
    return records.v4.input(call.input()).sessionId();
  }

  public String traceTarget() {
    return records.trace.input(call.input()).sessionId();
  }

  public String searchTarget() {
    return records.searchContract(task).input(call.input()).sessionId();
  }

  public String nativeReadTarget() {
    return records.contracts.input(call.input()).sessionId();
  }

  public Scope requireScope() {
    var scope = records.scopes.require(task);
    return id -> scope.sources().stream().anyMatch(t -> id.equals(s(t, "id")));
  }
}
