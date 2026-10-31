package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Current Connector step consumption; the seed is a shape fixture, never a business pass. */
final class SessionModelStepSqlScenario {
  private static final String STATEMENT =
      "io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.selectModelStep";
  private static final String STEP = "20000000-0000-0000-0000-000000000020";

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  static void verify(JdbcTemplate db, String task) {
    var source = db.getDataSource();
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var mappers = LedgerMapperSessions.create(source);
    String raw = "x".repeat(8159) + "字😀x";
    db.update(
        "UPDATE step SET request_snapshot=?,request_digest=? WHERE id=?",
        raw,
        Payloads.hash(raw),
        STEP);
    var before = SessionDependencyScenario.snapshot(db);
    long prior = 0;
    for (boolean folded : List.of(false, true, true)) {
      var q = query();
      String[] hash = {null};
      tx.required(
          q,
          () -> {
            SessionQueryTransaction.current(q).locked("tenant-a", task);
            java.util.function.BiConsumer<SessionQueryContext.Result, String> consume =
                (result, value) -> hash[0] = result.modelStepColumn("request_snapshot", value);
            var args =
                folded
                    ? Map.of(
                        "query",
                        q,
                        "tenant",
                        "tenant-a",
                        "stepId",
                        STEP,
                        "taskId",
                        task,
                        "modelStepHash",
                        consume)
                    : Map.of("query", q, "tenant", "tenant-a", "stepId", STEP, "taskId", task);
            if (folded)
              QueryResults.verifyModelStepCommand(
                  mappers.getConfiguration().getMappedStatement(STATEMENT), q, args);
            Map<String, Object> row = mappers.selectOne(STATEMENT, args);
            if (!folded)
              hash[0] =
                  new JdbcSessionRecords(db, q)
                      .policyHash(Map.of(), (String) row.get("request_snapshot"));
            check(
                hash[0].equals(row.get("request_digest")),
                "new SQL raw digest matches stored digest folded=" + folded);
            return null;
          });
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "22 tables unchanged folded=" + folded);
      check(q.scratchLive() == 65536, "all SQL and encoding owners released folded=" + folded);
      if (!folded) prior = q.resources().operations();
      else
        check(
            q.resources().operations() < prior,
            "complete step SQL/encoding/digest consumption deletes repeated traversal");
      System.out.println(
          "MODEL_STEP folded="
              + folded
              + " resources="
              + q.resources()
              + " completed="
              + q.completedWork()
              + " peak="
              + q.scratchPeak());
    }
    var faultSource = new SessionFinalMapperFailures.FaultSource(source);
    faultSource.table = "step";
    var faultDb = new JdbcTemplate(faultSource);
    var faultMappers = LedgerMapperSessions.create(faultSource);
    var faultTx =
        new SessionQueryTransaction(faultSource, new DataSourceTransactionManager(faultSource));
    for (String fault :
        List.of(
            "count",
            "label",
            "type",
            "write",
            "close-write",
            "error",
            "close",
            "foreign",
            "extra")) {
      var q = query();
      var foreign = query();
      faultSource.fault = fault;
      faultSource.changed = false;
      faultSource.closed = false;
      faultSource.getters = 0;
      faultSource.primary =
          fault.equals("error")
              ? new AssertionError("step getter Error")
              : new java.sql.SQLException("step close");
      faultSource.secondary =
          fault.equals("error") ? new java.sql.SQLException("step suppressed close") : null;
      faultSource.write =
          () ->
              faultMappers
                  .getMapper(
                      io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                          .SessionContextMapper.class)
                  .account(q, 1, "tenant-a", task);
      Throwable caught = null;
      try {
        faultTx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", task);
              java.util.function.BiConsumer<SessionQueryContext.Result, String> consume =
                  (result, value) -> result.modelStepColumn("request_snapshot", value);
              var args =
                  Map.of(
                      "query",
                      fault.equals("foreign") ? foreign : q,
                      "tenant",
                      "tenant-a",
                      "stepId",
                      STEP,
                      "taskId",
                      task,
                      "modelStepHash",
                      consume);
              try (var extra =
                  q.meter(
                      () ->
                          (label, value) -> {
                            if (fault.equals("extra")) faultSource.write.run();
                          })) {
                faultMappers.selectOne(STATEMENT, args);
              }
              return null;
            });
      } catch (RuntimeException | Error failure) {
        caught = failure;
      }
      check(
          caught != null && q.recordedFailure() != null, "step first error poisons Work " + fault);
      check(
          before.equals(SessionDependencyScenario.snapshot(faultDb)), "22-table rollback " + fault);
      check(
          q.scratchLive() == 65536 && foreign.scratchLive() == 65536,
          "all concurrent step owners released " + fault);
      if (List.of("count", "label", "type").contains(fault))
        check(faultSource.getters == 0, "full projection before dynamic getters " + fault);
      if (fault.equals("write"))
        check(faultSource.getters == 1, "write invalidates before the next identity getter");
      if (!fault.equals("foreign")) check(faultSource.closed, "actual Result closed " + fault);
      if (fault.equals("error"))
        check(
            SessionFinalMapperFailures.contains(caught, faultSource.primary)
                && SessionFinalMapperFailures.contains(faultSource.primary, faultSource.secondary),
            "original Error and suppressed close survive step cleanup");
    }
    for (String changed : List.of(raw + "changed", new String(raw))) {
      db.update(
          "UPDATE step SET request_snapshot=?,request_digest=?,session_upper_seq=0,session_context_digest=REPEAT('a',64),session_binding_digest=REPEAT('a',64) WHERE id=?",
          changed,
          "0".repeat(64),
          STEP);
      var corrupted = SessionDependencyScenario.snapshot(db);
      var q = query();
      io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure refusal = null;
      try {
        tx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", task);
              new JdbcSessionRecords(db, q, mappers)
                  .modelStep(
                      Map.of("id", task, "capabilities", SessionLineagePolicyV5.CAPABILITIES),
                      "tenant-a",
                      STEP,
                      task);
              return null;
            });
      } catch (io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure failure) {
        refusal = failure;
      }
      check(
          refusal != null && refusal.code().equals("SESSION_HISTORY_CORRUPT"),
          "fresh step raw with mismatched digest is rejected");
      check(
          q.completedWork().get("history.encode") == changed.length(),
          "fresh corrupt raw is independently encoded");
      check(
          corrupted.equals(SessionDependencyScenario.snapshot(db)),
          "corrupt step 22-table rollback");
      check(q.scratchLive() == 65536, "corrupt step owners released");
    }
  }
}
