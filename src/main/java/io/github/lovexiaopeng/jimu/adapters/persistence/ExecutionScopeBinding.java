package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator-only binding, never called by startup or an application request. */
public final class ExecutionScopeBinding {
  public record Input(
      ExecutionScopes.Scope scope,
      Map<String, ModelSnapshot> profiles,
      ExecutionScopes.AccountEvidence accountEvidence,
      String boundBy) {}

  private final JdbcTemplate db;
  private final TransactionTemplate transactions;
  private final JdbcExecutionScopes scopes;

  public ExecutionScopeBinding(DataSource operator, ModelProfiles models) {
    db = new JdbcTemplate(operator);
    db.setQueryTimeout(5);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(operator));
    transactions.setTimeout(10);
    scopes = new JdbcExecutionScopes(db, models);
  }

  public ExecutionScopeBinding(DataSource operator, ExecutionScopes.Requirement requirement) {
    db = new JdbcTemplate(operator);
    db.setQueryTimeout(5);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(operator));
    transactions.setTimeout(10);
    scopes = new JdbcExecutionScopes(db, requirement);
  }

  public Map<String, Object> bindOnce(Input input) {
    if (input == null || input.profiles() == null)
      throw new TaskFailure(409, "EVALUATION_SCOPE_NOT_ALLOWED");
    var scope = input.scope();
    String json = ExecutionScopes.canonical(scope), digest = Payloads.hash(json);
    if (input.boundBy() == null || input.boundBy().isBlank() || input.boundBy().length() > 160)
      throw new TaskFailure(409, "EVALUATION_SCOPE_NOT_ALLOWED");
    return transactions.execute(
        status -> {
          var runs =
              db.queryForList(
                  "SELECT * FROM evaluation_budget WHERE tenant_id=? AND id=? FOR UPDATE",
                  scope.tenantId(),
                  scope.runId());
          if (runs.size() != 1) throw new TaskFailure(409, "EVALUATION_SCOPE_NOT_ALLOWED");
          var run = runs.getFirst();
          scopes.verifyBinding(scope, input.profiles(), input.accountEvidence(), run);
          if (!"OPEN".equals(run.get("state"))) throw new TaskFailure(409, "MODEL_BUDGET_EXCEEDED");
          var existing =
              db.queryForList(
                  "SELECT * FROM evaluation_execution_scope WHERE tenant_id=? AND run_id=?",
                  scope.tenantId(),
                  scope.runId());
          if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!json.equals(row.get("scope_snapshot")) || !digest.equals(row.get("scope_digest")))
              throw new TaskFailure(409, "EVALUATION_SCOPE_BINDING_CONFLICT");
            return row; // First timestamp/actor and all original accounting remain unchanged.
          }
          var budget =
              db.queryForList(
                  "SELECT * FROM tenant_budget WHERE tenant_id=? FOR UPDATE", scope.tenantId());
          if (budget.size() != 1
              || !"OPEN".equals(run.get("state"))
              || ((Number) run.get("max_requests")).longValue() > 63
              || ((Number) run.get("max_cost_micros")).longValue() > 500000000
              || ((Number) run.get("requests_used")).longValue()
                  != db.queryForObject(
                      "SELECT COUNT(*) FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=?",
                      Long.class,
                      scope.tenantId(),
                      scope.runId())) throw new TaskFailure(409, "EVALUATION_SCOPE_NOT_ALLOWED");
          long accounted =
              db.queryForObject(
                  "SELECT COALESCE(SUM(COALESCE(d.actual_cost,d.reserved_cost)),0) FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=?",
                  Long.class,
                  scope.tenantId(),
                  scope.runId());
          if (accounted != ((Number) run.get("cost_held")).longValue()
              || db.queryForObject(
                      "SELECT COUNT(*) FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id LEFT JOIN reservation r ON r.tenant_id=d.tenant_id AND r.id=CONCAT(d.id,':cost') WHERE t.tenant_id=? AND t.evaluation_run_id=? AND (r.id IS NULL OR r.task_id<>d.task_id OR r.amount<>d.reserved_cost OR NOT(r.actual <=> d.actual_cost))",
                      Long.class,
                      scope.tenantId(),
                      scope.runId())
                  != 0) throw new TaskFailure(409, "EVALUATION_SCOPE_LEDGER_UNPROVEN");
          // No budget updates, task backfill, run creation, or implicit recovery.
          db.update(
              "INSERT INTO evaluation_execution_scope(tenant_id,run_id,scope_snapshot,scope_digest,bound_at_utc,bound_by) VALUES(?,?,?,?,UTC_TIMESTAMP(6),?)",
              scope.tenantId(),
              scope.runId(),
              json,
              digest,
              input.boundBy());
          return db.queryForMap(
              "SELECT * FROM evaluation_execution_scope WHERE tenant_id=? AND run_id=?",
              scope.tenantId(),
              scope.runId());
        });
  }
}
