package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only operator assessment. No client result import, settlement, state change or replay. */
public final class WebFetchReconciliation {
  private final JdbcTemplate db;
  private final TransactionTemplate transactions;

  public WebFetchReconciliation(DataSource source) {
    db = new JdbcTemplate(source);
    db.setQueryTimeout(5);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.setReadOnly(true);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transactions.setTimeout(10);
  }

  public Map<String, Object> assess(String tenant, String taskId, String invocationId) {
    return transactions.execute(
        status -> {
          var tasks =
              db.queryForList("SELECT * FROM task WHERE tenant_id=? AND id=?", tenant, taskId);
          var invocations =
              db.queryForList(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  tenant,
                  taskId,
                  invocationId);
          if (tasks.size() != 1 || invocations.size() != 1) throw new TaskFailure(404, "NOT_FOUND");
          var task = tasks.getFirst();
          var invocation = invocations.getFirst();
          var proof =
              db.queryForList(
                  "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=? AND type='web.result_preserved'",
                  tenant,
                  taskId,
                  "web-result:" + invocationId);
          if (proof.size() != 1
              || !(invocation.get("output_snapshot") instanceof String output)
              || !Payloads.hash(output).equals(invocation.get("output_digest")))
            throw new TaskFailure(409, "WEB_RESULT_PROOF_MISSING");
          var steps =
              db.queryForList(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                  tenant,
                  taskId,
                  invocation.get("step_id"));
          if (steps.size() != 1
              || !WebFetchReceipts.digest(task, steps.getFirst(), invocation, output)
                  .equals(proof.getFirst().get("code")))
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
          return Map.of(
              "taskId",
              taskId,
              "invocationId",
              invocationId,
              "state",
              invocation.get("state"),
              "outputDigest",
              invocation.get("output_digest"),
              "receiptDigest",
              proof.getFirst().get("code"),
              "assessment",
              "PROVEN_RESULT_ONLY_NO_RESUME_OR_SETTLEMENT",
              "readOnly",
              true);
        });
  }
}
