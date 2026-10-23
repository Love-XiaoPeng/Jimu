package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * InnoDB is the only authority. Every write locks budget before task and checks ownership inside
 * the transaction.
 */
public final class JdbcLedger
    implements Tasks,
        ExecutionLedger,
        NativeSearchLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.TodoLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger {
  private final JdbcTemplate db;
  private final ThreadLocal<Long> sessionQueryDeadline = new ThreadLocal<>();
  private final TransactionTemplate transactions;
  private final ModelProfiles models;
  private final JdbcExecutionScopes executionScopes;
  private java.util.function.BooleanSupplier searchAvailable = () -> false;
  private final JdbcImageRecords images;
  private final JdbcMcpRecords mcp;
  private ArtifactStore imageStore;
  private final io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts
      questions = new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts();
  private boolean questionsEnabled;
  private boolean todosEnabled;
  private final io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts todos =
      new io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts();

  private boolean directoryEnabled;
  private boolean sessionReadEnabled;
  private final JdbcSessionRecords sessions;

  public void sessionReadEnabled(boolean enabled, java.util.List<String> sensitive) {
    sessionReadEnabled = enabled;
    sessions.sensitive(sensitive);
  }

  private void sessionAccess(Map<String, Object> task) {
    if (!JdbcSessionRecords.task(task)) throw new TaskFailure(409, "SESSION_HISTORY_UNAVAILABLE");
    sessions.history(task, false);
    var pin = tools(task).pins().get("session_event_read");
    var installed =
        one(
            "SELECT * FROM installation WHERE tenant_id=? AND plugin_id='session_event_read' FOR SHARE",
            s(task, "tenant_id"));
    if (!pin.version().equals(s(installed, "plugin_version"))
        || !pin.digest().equals(s(installed, "plugin_digest"))
        || pin.installationEpoch() != n(installed, "epoch")
        || !pin.version().equals(sessions.contracts.descriptor().version())
        || !pin.digest().equals(sessions.contracts.descriptor().outputSha256()))
      throw new TaskFailure(403, "CAPABILITY_REVOKED");
  }

  private void sessionAdmission(Map<String, Object> task) {
    if (!sessionReadEnabled) throw new TaskFailure(403, "SESSION_READ_DISABLED");
    sessions.history(task, true);
    models.requireSessionRead(snapshot(task));
  }

  private boolean sessionAvailable(Identity identity) {
    if (!sessionReadEnabled) return false;
    try {
      models.requireSessionRead(models.selected(identity.tenant()));
      var installed =
          one(
              "SELECT plugin_version,plugin_digest FROM installation WHERE tenant_id=? AND plugin_id='session_event_read'",
              identity.tenant());
      return sessions.contracts.descriptor().version().equals(s(installed, "plugin_version"))
          && sessions.contracts.descriptor().outputSha256().equals(s(installed, "plugin_digest"));
    } catch (TaskFailure rejected) {
      return false;
    }
  }

  private <T> T sessionTransaction(java.util.function.Supplier<T> work) {
    var transaction = new TransactionTemplate(transactions.getTransactionManager());
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(2);
    long started = System.nanoTime();
    sessionQueryDeadline.set(started + 2_000_000_000L);
    try (var deadline = new SessionQueryDeadline(sessionQueryDeadline.get())) {
      return transaction.execute(
          status ->
              db.execute(
                  (org.springframework.jdbc.core.ConnectionCallback<T>)
                      connection -> {
                        int priorNetwork = connection.getNetworkTimeout();
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(
                                new org.springframework.transaction.support
                                    .TransactionSynchronization() {
                                  @Override
                                  public void beforeCommit(boolean readOnly) {
                                    try {
                                      connection.setNetworkTimeout(
                                          Runnable::run, sessionRemainingMillis());
                                    } catch (java.sql.SQLException failure) {
                                      throw new org.springframework.dao
                                          .DataAccessResourceFailureException(
                                          "Session query unavailable", failure);
                                    }
                                  }

                                  @Override
                                  public void afterCompletion(int completion) {
                                    try {
                                      if (!connection.isClosed())
                                        connection.setNetworkTimeout(Runnable::run, priorNetwork);
                                    } catch (java.sql.SQLException failure) {
                                      throw new org.springframework.dao
                                          .DataAccessResourceFailureException(
                                          "Session connection reset unavailable", failure);
                                    }
                                  }
                                });
                        connection.setNetworkTimeout(Runnable::run, sessionRemainingMillis());
                        int priorWait, priorMax;
                        try (var statement = connection.createStatement();
                            var result =
                                statement.executeQuery(
                                    "SELECT @@SESSION.innodb_lock_wait_timeout,@@SESSION.max_execution_time")) {
                          result.next();
                          priorWait = result.getInt(1);
                          priorMax = result.getInt(2);
                        }
                        try (var statement = connection.createStatement()) {
                          statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                          statement.execute("SET SESSION max_execution_time=2000");
                        }
                        try {
                          T result = work.get();
                          if (System.nanoTime() - started >= 2_000_000_000L)
                            throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
                          return result;
                        } finally {
                          try (var statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout=" + priorWait);
                            statement.execute("SET SESSION max_execution_time=" + priorMax);
                          }
                        }
                      }));
    } catch (RuntimeException failure) {
      if (System.nanoTime() - started >= 2_000_000_000L
          && (failure instanceof org.springframework.dao.DataAccessException
              || failure
                  instanceof org.springframework.transaction.CannotCreateTransactionException))
        throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
      throw failure;
    } finally {
      sessionQueryDeadline.remove();
    }
  }

  private int sessionRemainingMillis() {
    long remaining = sessionQueryDeadline.get() - System.nanoTime();
    if (remaining <= 0) throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    return (int) Math.max(1, (remaining + 999999L) / 1000000L);
  }

  @Override
  public String sessionContext(Lease lease) {
    return sessionTransaction(
        () -> {
          var task = fenced(lease, true);
          String context = sessions.context(task);
          sessions.account(task, false);
          return context;
        });
  }

  private Map<String, Object> sessionOwner(Identity identity, String taskId) {
    if (!taskId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    // Same lock order as worker commits: bucket -> task -> actor/installation -> history.
    bucket(identity.tenant());
    var found =
        rows(
            "SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=? FOR UPDATE",
            identity.tenant(),
            taskId,
            identity.actor());
    if (found.isEmpty()) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var task = found.getFirst();
    identity(identity);
    access(task);
    sessionAccess(task);
    return task;
  }

  private <T> T sessionOwnerRead(java.util.function.Supplier<T> work) {
    long started = System.nanoTime();
    try {
      return sessionTransaction(work);
    } catch (org.springframework.dao.QueryTimeoutException
        | org.springframework.dao.CannotAcquireLockException e) {
      throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException e) {
      if (System.nanoTime() - started >= 2_000_000_000L)
        throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
      throw new TaskFailure(503, "SESSION_QUERY_UNAVAILABLE");
    }
  }

  @Override
  public String sessionHistory(Identity identity, String taskId) {
    return sessionOwnerRead(() -> sessions.metadataView(sessionOwner(identity, taskId)));
  }

  @Override
  public String sessionEvent(Identity identity, String taskId, long seq, int before, int after) {
    return sessionOwnerRead(
        () -> {
          var task = sessionOwner(identity, taskId);
          var input =
              sessions.contracts.input(
                  Payloads.json(Map.of("seq", seq, "before", before, "after", after)));
          long upper = n(sessions.history(task, true), "next_seq") - 1;
          return sessions.read(task, input, upper, "owner-snapshot").output();
        });
  }

  private long sessionAppend(
      Map<String, Object> task,
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String sourceDigest,
      Lease lease) {
    String changed = "history:" + fact;
    event(task, changed, "session_history.changed", null);
    long reliable =
        db.queryForObject(
            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"),
            changed);
    long seq =
        sessions.append(task, fact, type, source, data, redacted, sourceDigest, lease, reliable);
    sessions.account(task, type.equals("turn.ended"));
    return seq;
  }

  private void sessionFact(Map<String, Object> task, String fact) {
    if (!JdbcSessionRecords.task(task)) return;
    if (fact.equals("accepted")) {
      var text = sessions.contracts.publicText(s(task, "goal"), sessions.sensitive());
      sessionAppend(
          task,
          "accepted-user",
          "user.message",
          sessions.source("request", null, null),
          Map.of("content", text.content()),
          text.redacted(),
          s(task, "request_digest"),
          null);
    } else if (fact.startsWith("prepared:") || fact.startsWith("response:")) {
      boolean model = fact.startsWith("response:");
      String stepId = fact.substring(fact.indexOf(':') + 1);
      var st =
          one(
              "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
              s(task, "tenant_id"),
              stepId,
              s(task, "id"));
      if (model && !"RESPONDED".equals(s(st, "state"))) return;
      var attempt =
          one(
              "SELECT * FROM task_attempt WHERE tenant_id=? AND id=? AND task_id=?",
              s(task, "tenant_id"),
              s(task, "current_attempt_id"),
              s(task, "id"));
      Lease lease =
          new Lease(
              s(task, "tenant_id"),
              s(task, "owner_actor_id"),
              s(task, "id"),
              s(attempt, "id"),
              n(attempt, "fence"),
              s(task, "worker_id"),
              time(task, "deadline"),
              s(task, "goal"),
              caps(task),
              n(task, "installation_epoch"));
      Map<String, Object> data;
      boolean redacted = false;
      if (model) {
        var response =
            Payloads.read(
                s(st, "response_snapshot"),
                io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response.class);
        var text = sessions.contracts.publicText(response.content(), sessions.sensitive());
        redacted = text.redacted();
        data = Map.of("content", text.content(), "calls", response.calls());
      } else data = Map.of("ordinal", n(st, "ordinal"));
      sessionAppend(
          task,
          (model ? "response:" : "step:") + stepId,
          model ? "model.response" : "step.started",
          sessions.source(model ? "model" : "step", stepId, null),
          data,
          redacted,
          s(st, model ? "response_digest" : "request_digest"),
          lease);
    } else if (fact.equals("terminal")) {
      var terminal =
          one(
              "SELECT event_id,type,status,code FROM event WHERE tenant_id=? AND task_id=? AND fact_id='terminal'",
              s(task, "tenant_id"),
              s(task, "id"));
      var data = new java.util.LinkedHashMap<String, Object>();
      data.put("state", terminal.get("status"));
      data.put("code", terminal.get("code"));
      sessionAppend(
          task,
          "terminal",
          "turn.ended",
          sessions.source("task", null, null),
          data,
          false,
          Payloads.hash(Payloads.json(terminal)),
          null);
    }
  }

  @Override
  public String readOrResult(Lease lease, String stepId, Call call, String version, String digest) {
    AdmissionResult<String> result;
    try {
      result =
          sessionTransaction(
              () -> {
                var task = fenced(lease, true);
                if (!JdbcSessionRecords.task(task))
                  throw new TaskFailure(403, "SESSION_READ_DISABLED");
                var i = originalDirectorySlot(lease, stepId, call, version, digest);
                var input = sessions.contracts.input(call.input());
                if (!call.tool().equals("session_event_read"))
                  throw new TaskFailure(409, "INVOCATION_MISMATCH");
                var h = sessions.history(task, true);
                var st =
                    one(
                        "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                        lease.tenant(),
                        stepId,
                        lease.taskId());
                sessions.validateStep(task, st);
                var b =
                    one(
                        "SELECT * FROM invocation_session_read WHERE tenant_id=? AND invocation_id=? FOR UPDATE",
                        lease.tenant(),
                        s(i, "id"));
                if (n(b, "record_version") != 1
                    || !lease.taskId().equals(s(b, "task_id"))
                    || !stepId.equals(s(b, "step_id"))
                    || !s(h, "binding_digest").equals(s(b, "binding_digest"))
                    || !s(h, "binding_digest").equals(s(st, "session_binding_digest"))
                    || n(b, "upper_seq") != n(st, "session_upper_seq")
                    || !Payloads.hash(call.input()).equals(s(b, "input_digest"))
                    || n(b, "target_seq") != input.seq()
                    || n(b, "before_count") != input.before()
                    || n(b, "after_count") != input.after()) throw JdbcSessionRecords.corrupt();
                if (!"PENDING".equals(s(i, "state"))) {
                  if (!s(i, "state").equals(s(b, "outcome"))) throw JdbcSessionRecords.corrupt();
                  var receipt =
                      one(
                          "SELECT * FROM session_public_event WHERE tenant_id=? AND task_id=? AND seq=?",
                          lease.tenant(),
                          lease.taskId(),
                          n(b, "receipt_seq"));
                  sessions.verify(task, receipt);
                  todoCommitFence(lease);
                  sessions.account(task, false);
                  if ("FAILED".equals(s(b, "outcome")))
                    return new AdmissionResult<String>(
                        null,
                        new TaskFailure(
                            sessionFailureStatus(s(b, "failure_code")), s(b, "failure_code")));
                  if (!"SUCCEEDED".equals(s(b, "outcome"))) throw JdbcSessionRecords.corrupt();
                  sessions.contracts.output(s(i, "output_snapshot"));
                  return new AdmissionResult<String>(s(i, "output_snapshot"), null);
                }
                if (!"PENDING".equals(s(b, "outcome"))) throw JdbcSessionRecords.corrupt();
                var budget = bucket(lease.tenant());
                if (n(task, "tools_used") >= n(task, "max_tools")
                    || n(task, "tools_used") >= 20
                    || n(budget, "tool_used") >= n(budget, "tool_limit"))
                  throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
                JdbcSessionRecords.Read read = null;
                TaskFailure failure = null;
                try {
                  read = sessions.read(task, input, n(b, "upper_seq"), "pre-step");
                } catch (TaskFailure rejected) {
                  if (!List.of(
                          "SESSION_EVENT_NOT_FOUND",
                          "SESSION_HISTORY_CORRUPT",
                          "SESSION_HISTORY_VERSION_UNSUPPORTED",
                          "SESSION_HISTORY_LIMIT")
                      .contains(rejected.code())) throw rejected;
                  failure = rejected;
                }
                String invocation = s(i, "id"),
                    output = read == null ? null : read.output(),
                    code = failure == null ? null : failure.code();
                if (output != null)
                  sessions.body(
                      task,
                      io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                          output));
                db.update(
                    "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    lease.taskId());
                db.update(
                    "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?",
                    lease.tenant());
                reserve(
                    lease.tenant(),
                    lease.taskId(),
                    invocation,
                    invocation,
                    "TOOL",
                    1,
                    "SETTLED",
                    "KNOWN");
                db.update(
                    "UPDATE reservation SET actual=1 WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    invocation);
                String outcome = failure == null ? "SUCCEEDED" : "FAILED";
                db.update(
                    "UPDATE invocation SET state=?,output_snapshot=?,output_digest=? WHERE tenant_id=? AND id=? AND state='PENDING'",
                    outcome,
                    output,
                    output == null ? null : Payloads.hash(output),
                    lease.tenant(),
                    invocation);
                long receipt = n(sessions.history(task, true), "next_seq");
                db.update(
                    "UPDATE invocation_session_read SET outcome=?,failure_code=?,window_start=?,window_end=?,target_digest=?,output_digest=?,output_bytes=?,receipt_seq=?,committed_attempt_id=?,committed_fence=?,committed_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND invocation_id=? AND outcome='PENDING'",
                    outcome,
                    code,
                    read == null ? null : read.from(),
                    read == null ? null : read.to(),
                    read == null ? null : read.targetDigest(),
                    output == null ? null : Payloads.hash(output),
                    output == null
                        ? 0
                        : io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts
                            .bytes(output),
                    receipt,
                    lease.attemptId(),
                    lease.fence(),
                    lease.tenant(),
                    invocation);
                event(task, "intent:" + invocation, "tool.dispatched", null);
                event(
                    task,
                    "result:" + invocation,
                    failure == null ? "tool.completed" : "tool.failed",
                    code);
                var committed =
                    one(
                        "SELECT * FROM invocation_session_read WHERE tenant_id=? AND invocation_id=?",
                        lease.tenant(),
                        invocation);
                sessionAppend(
                    task,
                    "read:" + invocation,
                    "session.read",
                    sessions.source("invocation", stepId, invocation),
                    sessions.receipt(committed),
                    false,
                    s(i, "input_digest"),
                    lease);
                todoCommitFence(lease);
                sessions.account(task, false);
                return new AdmissionResult<String>(output, failure);
              });
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unconfirmed) {
      throw new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed();
    }
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private static int sessionFailureStatus(String code) {
    return switch (code) {
      case "SESSION_EVENT_NOT_FOUND" -> 404;
      case "SESSION_HISTORY_EXPIRED" -> 410;
      case "SESSION_HISTORY_LIMIT" -> 429;
      default -> 409;
    };
  }

  private boolean sessionCleanupSafe(Map<String, Object> task) {
    return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state"))
        && writersQuiescent(task)
        && rows(
                "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN') LIMIT 1",
                s(task, "tenant_id"),
                s(task, "id"))
            .isEmpty()
        && rows(
                "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND (state='HELD_UNKNOWN' OR usage_quality='UNKNOWN' OR (state='RESERVED' AND category<>'SESSION_HISTORY_STORAGE')) LIMIT 1",
                s(task, "tenant_id"),
                s(task, "id"))
            .isEmpty()
        && rows(
                "SELECT id FROM model_dispatch WHERE tenant_id=? AND task_id=? AND state IN ('DISPATCHED','USAGE_UNKNOWN','BOUND_EXCEEDED') LIMIT 1",
                s(task, "tenant_id"),
                s(task, "id"))
            .isEmpty();
  }

  private void cleanupSessionHistory() {
    var candidates =
        rows(
            """
        SELECT p.tenant_id,p.task_id FROM task_session_history p JOIN task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id
        WHERE p.content_state='PRESENT' AND p.retain_until<=CURRENT_TIMESTAMP(6) AND t.state IN ('SUCCEEDED','FAILED','CANCELLED')
        AND NOT EXISTS(SELECT 1 FROM task_attempt a WHERE a.tenant_id=p.tenant_id AND a.task_id=p.task_id AND a.quiescent=FALSE)
        AND NOT EXISTS(SELECT 1 FROM invocation i WHERE i.tenant_id=p.tenant_id AND i.task_id=p.task_id AND i.state IN ('RUNNING','OUTCOME_UNKNOWN'))
        AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.tenant_id=p.tenant_id AND r.task_id=p.task_id AND (r.state='HELD_UNKNOWN' OR r.usage_quality='UNKNOWN' OR (r.state='RESERVED' AND r.category<>'SESSION_HISTORY_STORAGE')))
        AND NOT EXISTS(SELECT 1 FROM model_dispatch d WHERE d.tenant_id=p.tenant_id AND d.task_id=p.task_id AND d.state IN ('DISPATCHED','USAGE_UNKNOWN','BOUND_EXCEEDED'))
        ORDER BY p.retain_until,p.tenant_id,p.task_id LIMIT 16
        """);
    for (var candidate : candidates)
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id"), id = s(candidate, "task_id");
            bucket(tenant);
            var task = locked(tenant, id);
            var h = sessions.history(task, false);
            if (!"PRESENT".equals(s(h, "content_state"))
                || n(h, "retained") == 1
                || !sessionCleanupSafe(task)) return null;
            sessions.purge(task);
            event(task, "session-history-purged", "session_history.content_deleted", null);
            sessions.release(task);
            return null;
          });
  }

  private io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore directoryWorkspace;

  public void directoryWorkspace(
      io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore workspace) {
    directoryWorkspace = workspace;
  }

  private boolean directoryCleanupSafe(Map<String, Object> task) {
    String tenant = s(task, "tenant_id"), id = s(task, "id");
    return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state"))
        && writersQuiescent(task)
        && rows(
                "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND (state='HELD_UNKNOWN' OR usage_quality='UNKNOWN' OR (state='RESERVED' AND category<>'DIRECTORY_STORAGE')) LIMIT 1",
                tenant,
                id)
            .isEmpty()
        && rows(
                "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN') LIMIT 1",
                tenant,
                id)
            .isEmpty()
        && rows(
                "SELECT id FROM model_dispatch WHERE tenant_id=? AND task_id=? AND state='DISPATCHED' LIMIT 1",
                tenant,
                id)
            .isEmpty();
  }

  private void cleanupDirectories() {
    if (directoryWorkspace == null) return;
    for (var candidate :
        rows(
            """
      SELECT p.tenant_id,p.task_id FROM task_directory p JOIN task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id
      WHERE p.content_state<>'PURGED' AND p.retain_until<=CURRENT_TIMESTAMP(6) AND t.state IN ('SUCCEEDED','FAILED','CANCELLED')
      AND NOT EXISTS(SELECT 1 FROM native_search_run n WHERE n.tenant_id=p.tenant_id AND n.task_id=p.task_id AND n.phase<>'STOPPED')
      AND NOT EXISTS(SELECT 1 FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id WHERE a.tenant_id=p.tenant_id AND a.task_id=p.task_id AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE))
      AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.tenant_id=p.tenant_id AND r.task_id=p.task_id AND (r.state='HELD_UNKNOWN' OR r.usage_quality='UNKNOWN' OR (r.state='RESERVED' AND r.category<>'DIRECTORY_STORAGE')))
      AND NOT EXISTS(SELECT 1 FROM invocation i WHERE i.tenant_id=p.tenant_id AND i.task_id=p.task_id AND i.state IN ('RUNNING','OUTCOME_UNKNOWN'))
      AND NOT EXISTS(SELECT 1 FROM model_dispatch d WHERE d.tenant_id=p.tenant_id AND d.task_id=p.task_id AND d.state='DISPATCHED')
      ORDER BY p.retain_until,p.tenant_id,p.task_id LIMIT 32
      """)) {
      String tenant = s(candidate, "tenant_id"), taskId = s(candidate, "task_id");
      var purge =
          tx(
              () -> {
                bucket(tenant);
                var task = locked(tenant, taskId);
                var p = directoryProjection(task);
                if ("PURGED".equals(s(p, "content_state"))
                    || time(p, "retain_until").isAfter(questionNow())
                    || !directoryCleanupSafe(task)) return null;
                if ("PURGING".equals(s(p, "content_state"))) return p;
                var retained = new java.util.LinkedHashMap<String, Object>();
                for (String field : List.of("request_snapshot", "goal", "summary"))
                  retainQuestionBody(retained, "task:" + field, s(task, field));
                String tombstone = "{\"version\":1,\"state\":\"DIRECTORY_PURGED\"}";
                for (var step :
                    rows("SELECT * FROM step WHERE tenant_id=? AND task_id=?", tenant, taskId)) {
                  for (String field :
                      List.of("request_snapshot", "response_snapshot", "directory_cwd"))
                    retainQuestionBody(
                        retained, "step:" + s(step, "id") + ":" + field, s(step, field));
                  db.update(
                      "UPDATE step SET request_snapshot=?,response_snapshot=IF(response_snapshot IS NULL,NULL,?),request_cleanup_state='DIRECTORY_PURGED',retained_request_digest=request_digest,directory_cwd=NULL WHERE tenant_id=? AND id=?",
                      tombstone,
                      tombstone,
                      tenant,
                      s(step, "id"));
                }
                for (var i :
                    rows(
                        "SELECT * FROM invocation WHERE tenant_id=? AND task_id=?",
                        tenant,
                        taskId)) {
                  for (String field : List.of("input_snapshot", "output_snapshot"))
                    retainQuestionBody(
                        retained, "invocation:" + s(i, "id") + ":" + field, s(i, field));
                  db.update(
                      "UPDATE invocation SET input_snapshot=?,output_snapshot=IF(output_snapshot IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                      tombstone,
                      tombstone,
                      tenant,
                      s(i, "id"));
                }
                for (var f :
                    rows(
                        "SELECT * FROM invocation_path_binding WHERE tenant_id=? AND task_id=?",
                        tenant,
                        taskId)) {
                  for (String field :
                      List.of(
                          "base_cwd",
                          "target_cwd",
                          "canonical_paths_snapshot",
                          "directory_evidence_snapshot"))
                    retainQuestionBody(
                        retained, "path:" + s(f, "invocation_id") + ":" + field, s(f, field));
                  db.update(
                      "UPDATE invocation_path_binding SET base_cwd=NULL,target_cwd=NULL,canonical_paths_snapshot=NULL,directory_evidence_snapshot=NULL,content_state='PURGED' WHERE tenant_id=? AND invocation_id=?",
                      tenant,
                      s(f, "invocation_id"));
                }
                for (var d :
                    rows(
                        "SELECT * FROM model_dispatch WHERE tenant_id=? AND task_id=?",
                        tenant,
                        taskId)) {
                  retainQuestionBody(
                      retained,
                      "dispatch:" + s(d, "id") + ":usage_snapshot",
                      s(d, "usage_snapshot"));
                  db.update(
                      "UPDATE model_dispatch SET usage_snapshot=IF(usage_snapshot IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                      tombstone,
                      tenant,
                      s(d, "id"));
                }
                retainQuestionBody(retained, "directory:cwd", s(p, "cwd"));
                String digests = Payloads.json(retained);
                directoryStorage(
                    task, digests.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                db.update(
                    "UPDATE task SET request_snapshot=?,goal=?,summary=IF(summary IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                    tombstone,
                    tombstone,
                    tombstone,
                    tenant,
                    taskId);
                db.update(
                    "UPDATE task_directory SET content_state='PURGING',cwd=NULL,retained_digests=?,cleanup_generation=? WHERE tenant_id=? AND task_id=? AND content_state='PRESENT'",
                    digests,
                    id(),
                    tenant,
                    taskId);
                return directoryProjection(task);
              });
      if (purge == null) continue;
      String generation = s(purge, "cleanup_generation");
      String receipt = s(purge, "filesystem_receipt");
      if (receipt == null) {
        String planned = Payloads.json(directoryWorkspace.planPurge(tenant, taskId, () -> {}));
        receipt =
            tx(
                () -> {
                  bucket(tenant);
                  var task = locked(tenant, taskId);
                  var p = directoryProjection(task);
                  if (!"PURGING".equals(s(p, "content_state"))
                      || !generation.equals(s(p, "cleanup_generation"))
                      || !directoryCleanupSafe(task))
                    throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
                  if (p.get("filesystem_receipt") != null) return s(p, "filesystem_receipt");
                  directoryStorage(
                      task, planned.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                  db.update(
                      "UPDATE task_directory SET filesystem_receipt=? WHERE tenant_id=? AND task_id=? AND filesystem_receipt IS NULL",
                      planned,
                      tenant,
                      taskId);
                  return planned;
                });
      }
      var plan =
          Payloads.read(
              receipt,
              io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore.PurgePlan.class);
      directoryWorkspace.purge(tenant, taskId, plan, () -> {});
      String confirmed = receipt;
      tx(
          () -> {
            bucket(tenant);
            var task = locked(tenant, taskId);
            var p = directoryProjection(task);
            if ("PURGED".equals(s(p, "content_state"))) return null;
            if (!"PURGING".equals(s(p, "content_state"))
                || !generation.equals(s(p, "cleanup_generation"))
                || !confirmed.equals(s(p, "filesystem_receipt"))
                || !directoryCleanupSafe(task)) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            db.update(
                "UPDATE task_directory SET content_state='PURGED',workspace_state='PURGED' WHERE tenant_id=? AND task_id=?",
                tenant,
                taskId);
            event(task, "directory-purged", "working_directory.content_deleted", null);
            if (db.update(
                    "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND category='DIRECTORY_STORAGE' AND state='RESERVED'",
                    tenant,
                    taskId + ":directory-storage")
                != 1) throw new TaskFailure(409, "RESERVATION_STATE");
            db.update(
                "UPDATE tenant_budget SET directory_storage_held=directory_storage_held-8388608 WHERE tenant_id=?",
                tenant);
            return null;
          });
    }
  }

  private final io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts
      directories = new io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts();

  public void directoryEnabled(boolean enabled) {
    directoryEnabled = enabled;
  }

  private boolean directoryTask(Map<String, Object> task) {
    return caps(task).contains("working_directory");
  }

  private Map<String, Object> directoryProjection(Map<String, Object> task) {
    var p =
        one(
            "SELECT * FROM task_directory WHERE tenant_id=? AND task_id=?",
            s(task, "tenant_id"),
            s(task, "id"));
    if (n(p, "record_version") != 1
        || n(p, "directory_version") != 1
        || n(p, "path_policy_version") != 1)
      throw new TaskFailure(409, "DIRECTORY_BINDING_UNSUPPORTED");
    String namespace = Payloads.hash(s(task, "tenant_id") + ":" + s(task, "id"));
    if (!namespace.equals(s(p, "namespace_digest"))
        || !Payloads.hash(s(p, "binding_snapshot")).equals(s(p, "binding_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    directories.binding(s(p, "binding_snapshot"), namespace);
    if (n(p, "revision") == 0
        && (p.get("source_invocation_id") != null
            || "PRESENT".equals(s(p, "content_state")) && !"/".equals(s(p, "cwd"))))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return p;
  }

  private void directoryAccess(Map<String, Object> task) {
    if (!new java.util.HashSet<>(caps(task))
        .equals(java.util.Set.of("working_directory", "read", "write", "edit")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    for (String name : caps(task)) {
      var pin = tools(task).pins().get(name);
      var installed =
          one(
              "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE",
              s(task, "tenant_id"),
              name);
      var descriptor =
          name.equals("working_directory")
              ? directories.descriptor()
              : directoryFileContract(name).descriptor();
      if (!pin.version().equals(descriptor.version())
          || !pin.digest().equals(descriptor.outputSha256())
          || !pin.version().equals(s(installed, "plugin_version"))
          || !pin.digest().equals(s(installed, "plugin_digest"))
          || pin.installationEpoch() != n(installed, "epoch"))
        throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    }
    directoryProjection(task);
  }

  private io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts.Contract
      directoryFileContract(String name) {
    try {
      return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts()
          .load(name);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void directoryAdmission(Map<String, Object> task) {
    if (!directoryEnabled) throw new TaskFailure(403, "DIRECTORY_FEATURE_DISABLED");
    if (!"PRESENT".equals(s(directoryProjection(task), "content_state")))
      throw new TaskFailure(410, "DIRECTORY_CONTENT_EXPIRED");
    models.requireDirectory(snapshot(task));
  }

  private void directoryStorage(Map<String, Object> task, long additional) {
    if (!directoryTask(task)) return;
    long bytes =
        db.queryForObject(
            """
      SELECT COALESCE(OCTET_LENGTH(t.request_snapshot),0)+COALESCE(OCTET_LENGTH(t.goal),0)+COALESCE(OCTET_LENGTH(t.summary),0)
        +COALESCE(OCTET_LENGTH(t.model_snapshot),0)+COALESCE(OCTET_LENGTH(t.tools_snapshot),0)
        +(SELECT OCTET_LENGTH(binding_snapshot)+COALESCE(OCTET_LENGTH(cwd),0)+COALESCE(OCTET_LENGTH(retained_digests),0)+COALESCE(OCTET_LENGTH(filesystem_receipt),0) FROM task_directory WHERE tenant_id=t.tenant_id AND task_id=t.id)
        +(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)+COALESCE(OCTET_LENGTH(directory_cwd),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)
        +(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)
        +(SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(usage_snapshot),0)),0) FROM model_dispatch WHERE tenant_id=t.tenant_id AND task_id=t.id)
        +(SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(base_cwd),0)+COALESCE(OCTET_LENGTH(target_cwd),0)+COALESCE(OCTET_LENGTH(canonical_paths_snapshot),0)+COALESCE(OCTET_LENGTH(directory_evidence_snapshot),0)),0) FROM invocation_path_binding WHERE tenant_id=t.tenant_id AND task_id=t.id)
      FROM task t WHERE tenant_id=? AND id=?
      """,
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"));
    if (bytes + additional + 65536 > 2097152) throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot
      directorySnapshot(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (!directoryTask(task)) throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
          var p = directoryProjection(task);
          return new io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot(
              s(p, "cwd"),
              n(p, "revision"),
              s(p, "binding_digest"),
              "READY".equals(s(p, "workspace_state")));
        });
  }

  @Override
  public void directoryReady(Lease lease) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var p = directoryProjection(task);
          if (!"UNINITIALIZED".equals(s(p, "workspace_state")))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          db.update(
              "UPDATE task_directory SET workspace_state='READY' WHERE tenant_id=? AND task_id=? AND workspace_state='UNINITIALIZED'",
              lease.tenant(),
              lease.taskId());
          todoCommitFence(lease);
          return null;
        });
  }

  private Map<String, Object> originalDirectorySlot(
      Lease lease, String stepId, Call call, String version, String digest) {
    var i =
        one(
            "SELECT i.*,s.state AS step_state,s.record_version AS step_version,s.response_snapshot,s.response_digest,s.task_id AS step_task,s.ordinal AS step_ordinal FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.tenant_id=? AND i.task_id=? AND i.step_id=? AND i.slot=?",
            lease.tenant(),
            lease.taskId(),
            stepId,
            call.slot());
    var task = one("SELECT * FROM task WHERE tenant_id=? AND id=?", lease.tenant(), lease.taskId());
    var pin = tools(task).pins().get(call.tool());
    if (pin == null
        || !pin.version().equals(version)
        || !pin.digest().equals(digest)
        || pin.installationEpoch() != n(i, "installation_epoch"))
      throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    if (!lease.taskId().equals(s(i, "step_task"))
        || !"RESPONDED".equals(s(i, "step_state"))
        || n(i, "record_version") != 1
        || n(i, "step_version") != 1
        || !Payloads.hash(s(i, "response_snapshot")).equals(s(i, "response_digest"))
        || !call.input().equals(s(i, "input_snapshot"))
        || !Payloads.hash(call.input()).equals(s(i, "input_digest"))
        || !call.providerId().equals(s(i, "provider_id"))
        || !call.tool().equals(s(i, "tool"))
        || !version.equals(s(i, "plugin_version"))
        || !digest.equals(s(i, "plugin_digest"))) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var response =
        Payloads.read(
            s(i, "response_snapshot"),
            io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response.class);
    if (response.version() != 2) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    int index = -1;
    for (int slot = 0; slot < response.calls().size(); slot++) {
      var wire = response.calls().get(slot);
      if (new Call(slot + ":" + wire.id(), wire.id(), wire.name(), wire.arguments()).equals(call))
        index = slot;
    }
    if (index < 0) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    for (int slot = 0; slot < index; slot++) {
      var wire = response.calls().get(slot);
      var prior =
          one(
              "SELECT state FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=?",
              lease.tenant(),
              lease.taskId(),
              stepId,
              slot + ":" + wire.id());
      if (!"SUCCEEDED".equals(s(prior, "state")))
        throw new TaskFailure(409, "DIRECTORY_SLOT_ORDER");
    }
    if (!rows(
            "SELECT i.id FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.tenant_id=? AND i.task_id=? AND s.ordinal<? AND i.state<>'SUCCEEDED' LIMIT 1",
            lease.tenant(),
            lease.taskId(),
            n(i, "step_ordinal"))
        .isEmpty()) throw new TaskFailure(409, "DIRECTORY_SLOT_ORDER");
    i.put("directory_slot_index", index);
    return i;
  }

  private Map<String, Object> freezeDirectorySlot(Map<String, Object> task, Map<String, Object> i) {
    var p = directoryProjection(task);
    if (!"READY".equals(s(p, "workspace_state"))) throw new TaskFailure(409, "FS_BINDING_UNPROVEN");
    var existing =
        rows(
            "SELECT * FROM invocation_path_binding WHERE tenant_id=? AND invocation_id=?",
            s(task, "tenant_id"),
            s(i, "id"));
    if (!existing.isEmpty()
        && (n(existing.getFirst(), "record_version") != 1
            || n(existing.getFirst(), "path_policy_version") != 1))
      throw new TaskFailure(409, "DIRECTORY_BINDING_UNSUPPORTED");
    String base = existing.isEmpty() ? s(p, "cwd") : s(existing.getFirst(), "base_cwd");
    long revision = existing.isEmpty() ? n(p, "revision") : n(existing.getFirst(), "base_revision");
    String mode, target = null;
    List<String> paths = List.of();
    if ("working_directory".equals(s(i, "tool"))) {
      var input = directories.input(s(i, "input_snapshot"));
      mode = input.cd() == null ? "READ" : "SET";
      target =
          input.cd() == null
              ? base
              : io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.resolve(
                  base, input.cd(), true);
    } else {
      mode = "FILES";
      var input = directories.json(s(i, "input_snapshot"), 65535);
      directoryFileContract(s(i, "tool")).in().validate(input);
      paths =
          List.of(
              io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.resolve(
                  base, input.path("file_path").textValue(), false));
    }
    String json = Payloads.json(paths);
    if (existing.isEmpty()) {
      directoryStorage(
          task,
          base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              + (target == null
                  ? 0
                  : target.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
              + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      db.update(
          "INSERT INTO invocation_path_binding(tenant_id,invocation_id,task_id,step_id,binding_digest,namespace_digest,slot_index,mode,base_revision,base_cwd,target_cwd,canonical_paths_snapshot,canonical_paths_digest,input_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          s(task, "tenant_id"),
          s(i, "id"),
          s(task, "id"),
          s(i, "step_id"),
          s(p, "binding_digest"),
          s(p, "namespace_digest"),
          n(i, "directory_slot_index"),
          mode,
          revision,
          base,
          target,
          json,
          Payloads.hash(json),
          s(i, "input_digest"));
    }
    var frozen =
        one(
            "SELECT * FROM invocation_path_binding WHERE tenant_id=? AND invocation_id=?",
            s(task, "tenant_id"),
            s(i, "id"));
    if (!"PRESENT".equals(s(frozen, "content_state"))
        || !s(p, "binding_digest").equals(s(frozen, "binding_digest"))
        || !s(p, "namespace_digest").equals(s(frozen, "namespace_digest"))
        || !s(i, "step_id").equals(s(frozen, "step_id"))
        || !s(task, "id").equals(s(frozen, "task_id"))
        || n(i, "directory_slot_index") != n(frozen, "slot_index")
        || !mode.equals(s(frozen, "mode"))
        || !java.util.Objects.equals(target, s(frozen, "target_cwd"))
        || !json.equals(s(frozen, "canonical_paths_snapshot"))
        || !Payloads.hash(json).equals(s(frozen, "canonical_paths_digest"))
        || !s(i, "input_digest").equals(s(frozen, "input_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!"SUCCEEDED".equals(s(i, "state"))
        && (revision != n(p, "revision") || !base.equals(s(p, "cwd"))))
      throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
    return frozen;
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen freezeDirectory(
      Lease lease, String stepId, Call call, String version, String digest) {
    if (!"working_directory".equals(call.tool()))
      throw new TaskFailure(409, "DIRECTORY_COMMIT_REQUIRED");
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (!directoryTask(task)) throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
          var i = originalDirectorySlot(lease, stepId, call, version, digest);
          var f = freezeDirectorySlot(task, i);
          String output = null;
          if ("SUCCEEDED".equals(s(i, "state"))) {
            directoryResultSource(task, i, f);
            output = s(i, "output_snapshot");
          } else if (!"PENDING".equals(s(i, "state")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          todoCommitFence(lease);
          return new io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen(
              s(i, "id"),
              s(f, "mode"),
              s(f, "base_cwd"),
              n(f, "base_revision"),
              s(f, "target_cwd"),
              List.of(),
              output);
        });
  }

  private void directoryResultSource(
      Map<String, Object> task, Map<String, Object> i, Map<String, Object> f) {
    if (!"SUCCEEDED".equals(s(i, "state"))
        || !"working_directory".equals(s(i, "tool"))
        || f.get("applied_revision") == null
        || f.get("committed_attempt_id") == null
        || !Payloads.hash(s(i, "output_snapshot")).equals(s(i, "output_digest"))
        || !directories.output(s(i, "output_snapshot")).cwd().equals(s(f, "target_cwd")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    boolean changed = Boolean.TRUE.equals(f.get("changed"));
    if (changed != !s(f, "base_cwd").equals(s(f, "target_cwd"))
        || changed && !"SET".equals(s(f, "mode"))
        || n(f, "applied_revision") != n(f, "base_revision") + (changed ? 1 : 0)
        || changed != (f.get("changed_event_seq") != null)
        || rows(
                    "SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?",
                    s(task, "tenant_id"),
                    s(task, "id"),
                    s(f, "committed_attempt_id"),
                    n(f, "committed_fence"))
                .size()
            != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (changed
        && rows(
                    "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND seq=? AND fact_id=? AND type='working_directory.changed'",
                    s(task, "tenant_id"),
                    s(task, "id"),
                    n(f, "changed_event_seq"),
                    "directory-change:" + s(i, "id"))
                .size()
            != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  @Override
  public io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result commitDirectory(
      Lease lease,
      String invocationId,
      io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Evidence evidence) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var p = directoryProjection(task);
          var original =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          var i =
              originalDirectorySlot(
                  lease,
                  s(original, "step_id"),
                  new Call(
                      s(original, "slot"),
                      s(original, "provider_id"),
                      s(original, "tool"),
                      s(original, "input_snapshot")),
                  s(original, "plugin_version"),
                  s(original, "plugin_digest"));
          var f = freezeDirectorySlot(task, i);
          if ("SUCCEEDED".equals(s(i, "state"))) {
            directoryResultSource(task, i, f);
            return directories.output(s(i, "output_snapshot"));
          }
          if (!"PENDING".equals(s(i, "state"))
              || !"working_directory".equals(s(i, "tool"))
              || f.get("applied_revision") != null)
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (!evidence.namespace().equals(s(p, "namespace_digest"))
              || !evidence.target().equals(s(f, "target_cwd"))
              || !evidence.filesDigest().matches("[a-f0-9]{64}"))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var budget = bucket(lease.tenant());
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          boolean changed = !s(f, "base_cwd").equals(s(f, "target_cwd"));
          long revision = n(f, "base_revision") + (changed ? 1 : 0);
          String output =
              Payloads.json(
                  new io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result(
                      s(f, "target_cwd")));
          directories.output(output);
          String proof = Payloads.json(evidence);
          directoryStorage(
              task,
              output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + proof.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
          db.update(
              "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              lease.taskId());
          db.update(
              "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "SETTLED",
              "KNOWN");
          db.update(
              "UPDATE reservation SET actual=1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "intent:" + invocationId, "tool.dispatched", null);
          Long seq = null;
          if (changed) {
            event(task, "directory-change:" + invocationId, "working_directory.changed", null);
            seq =
                db.queryForObject(
                    "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                    Long.class,
                    lease.tenant(),
                    lease.taskId(),
                    "directory-change:" + invocationId);
          }
          if (db.update(
                  "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=? WHERE tenant_id=? AND id=? AND state='PENDING'",
                  output,
                  Payloads.hash(output),
                  lease.tenant(),
                  invocationId)
              != 1) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (db.update(
                  "UPDATE invocation_path_binding SET applied_revision=?,changed=?,committed_attempt_id=?,committed_fence=?,changed_event_seq=?,committed_at=CURRENT_TIMESTAMP(6),directory_evidence_snapshot=? WHERE tenant_id=? AND invocation_id=? AND applied_revision IS NULL",
                  revision,
                  changed,
                  lease.attemptId(),
                  lease.fence(),
                  seq,
                  proof,
                  lease.tenant(),
                  invocationId)
              != 1) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (changed
              && db.update(
                      "UPDATE task_directory SET cwd=?,revision=?,source_invocation_id=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND revision=? AND content_state='PRESENT'",
                      s(f, "target_cwd"),
                      revision,
                      invocationId,
                      lease.tenant(),
                      lease.taskId(),
                      n(f, "base_revision"))
                  != 1) throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
          event(task, "result:" + invocationId, "tool.completed", null);
          todoCommitFence(lease);
          return directories.output(output);
        });
  }

  @Override
  public String directoryFilePath(Lease lease, String invocationId) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var i =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          i =
              originalDirectorySlot(
                  lease,
                  s(i, "step_id"),
                  new Call(s(i, "slot"), s(i, "provider_id"), s(i, "tool"), s(i, "input_snapshot")),
                  s(i, "plugin_version"),
                  s(i, "plugin_digest"));
          var f = freezeDirectorySlot(task, i);
          if (!"FILES".equals(s(f, "mode")) || !"RUNNING".equals(s(i, "state")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          return Payloads.read(
                  s(f, "canonical_paths_snapshot"), com.fasterxml.jackson.databind.JsonNode.class)
              .get(0)
              .textValue();
        });
  }

  @Override
  public Map<String, Object> getWorkingDirectory(Identity identity, String taskId) {
    try {
      UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, "NOT_FOUND");
    }
    return tx(
        () -> {
          var task = visible(identity, taskId);
          access(task);
          var r =
              one(
                  """
        SELECT t.next_event_seq,p.*,i.id AS invocation_id,i.task_id AS invocation_task,i.step_id,i.attempt_id,i.provider_id,i.tool,i.state AS invocation_state,i.output_snapshot,i.output_digest,
          f.mode,f.base_revision,f.base_cwd,f.target_cwd,f.applied_revision,f.changed,f.committed_attempt_id,f.committed_fence,f.changed_event_seq,f.committed_at
        FROM task t LEFT JOIN task_directory p ON p.tenant_id=t.tenant_id AND p.task_id=t.id
        LEFT JOIN invocation i ON i.tenant_id=p.tenant_id AND i.id=p.source_invocation_id
        LEFT JOIN invocation_path_binding f ON f.tenant_id=i.tenant_id AND f.invocation_id=i.id
        WHERE t.tenant_id=? AND t.id=? AND t.owner_actor_id=?
        """,
                  identity.tenant(),
                  taskId,
                  identity.actor());
          boolean absent = r.get("task_id") == null;
          var view = new java.util.LinkedHashMap<String, Object>();
          view.put("version", 1);
          view.put("taskId", taskId);
          view.put("asOfSeq", n(r, "next_event_seq"));
          view.put("revision", absent ? 0 : n(r, "revision"));
          view.put("state", absent ? "ABSENT" : s(r, "content_state"));
          view.put("cwd", absent ? null : s(r, "cwd"));
          view.put("source", null);
          view.put("retainUntil", absent ? null : time(r, "retain_until").toString());
          if (!absent && n(r, "revision") > 0) {
            if (!taskId.equals(s(r, "invocation_task"))
                || !"SUCCEEDED".equals(s(r, "invocation_state"))
                || !"working_directory".equals(s(r, "tool"))
                || !Boolean.TRUE.equals(r.get("changed"))
                || n(r, "revision") != n(r, "applied_revision"))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            if ("PRESENT".equals(s(r, "content_state"))) {
              if (!Payloads.hash(s(r, "output_snapshot")).equals(s(r, "output_digest"))
                  || !directories.output(s(r, "output_snapshot")).cwd().equals(s(r, "cwd"))
                  || !s(r, "cwd").equals(s(r, "target_cwd")))
                throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            } else {
              var retained =
                  Payloads.read(
                      s(r, "retained_digests"), com.fasterxml.jackson.databind.JsonNode.class);
              if (!retained
                  .path("invocation:" + s(r, "invocation_id") + ":output_snapshot")
                  .path("sha256")
                  .asText()
                  .equals(s(r, "output_digest"))) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            }
            if (rows(
                            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND seq=? AND fact_id=? AND type='working_directory.changed'",
                            identity.tenant(),
                            taskId,
                            n(r, "changed_event_seq"),
                            "directory-change:" + s(r, "invocation_id"))
                        .size()
                    != 1
                || rows(
                            "SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?",
                            identity.tenant(),
                            taskId,
                            s(r, "committed_attempt_id"),
                            n(r, "committed_fence"))
                        .size()
                    != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            view.put(
                "source",
                Map.of(
                    "invocationId",
                    s(r, "invocation_id"),
                    "checkpointId",
                    s(r, "step_id"),
                    "providerCallId",
                    s(r, "provider_id"),
                    "createdAttemptId",
                    s(r, "attempt_id"),
                    "committedAttemptId",
                    s(r, "committed_attempt_id"),
                    "fence",
                    n(r, "committed_fence"),
                    "eventId",
                    taskId + ":" + n(r, "changed_event_seq"),
                    "outputSha256",
                    s(r, "output_digest"),
                    "updatedAt",
                    time(r, "committed_at").toString()));
          }
          directories.view(view);
          return view;
        });
  }

  public void todosEnabled(boolean enabled) {
    todosEnabled = enabled;
  }

  private void directoryContext(Map<String, Object> task, String request) {
    var p = directoryProjection(task);
    var wire =
        Payloads.read(
            request,
            io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
    if (wire.version() != 2
        || request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072
        || wire.requestBody().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
      throw new TaskFailure(429, "MODEL_INPUT_TOO_LARGE");
    var messages = wire.messages();
    if (messages.isEmpty()
        || !messages.getLast().role().equals("user")
        || !directories.context(s(p, "cwd"), n(p, "revision")).equals(messages.getLast().content()))
      throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
    directoryStorage(
        task,
        request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + s(p, "cwd").getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
  }

  private boolean todoTask(Map<String, Object> task) {
    return caps(task).contains("todo_write");
  }

  private Map<String, Object> todoProjection(Map<String, Object> task) {
    var p =
        one(
            "SELECT * FROM task_todo_projection WHERE tenant_id=? AND task_id=?",
            s(task, "tenant_id"),
            s(task, "id"));
    if (n(p, "record_version") != 1 || n(p, "todo_version") != 1)
      throw new TaskFailure(409, "TODO_BINDING_UNSUPPORTED");
    if (!Payloads.hash(s(p, "binding_snapshot")).equals(s(p, "binding_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var binding =
        Payloads.read(s(p, "binding_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
    if (binding.path("version").asInt() != 1 || binding.path("todoVersion").asInt() != 1)
      throw new TaskFailure(409, "TODO_BINDING_UNSUPPORTED");
    if (!todos.binding().equals(s(p, "binding_snapshot")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return p;
  }

  private void todoAccess(Map<String, Object> task) {
    if (!caps(task).equals(List.of("todo_write"))) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var pin = tools(task).pins().get("todo_write");
    var installed =
        one(
            "SELECT * FROM installation WHERE tenant_id=? AND plugin_id='todo_write' FOR SHARE",
            s(task, "tenant_id"));
    if (!pin.version().equals(todos.descriptor().version())
        || !pin.digest().equals(todos.descriptor().outputSha256())
        || !pin.version().equals(s(installed, "plugin_version"))
        || !pin.digest().equals(s(installed, "plugin_digest"))
        || pin.installationEpoch() != n(installed, "epoch"))
      throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    todoProjection(task);
  }

  private void todoStorage(Map<String, Object> task, long additional) {
    if (!todoTask(task)) return;
    long bytes =
        db.queryForObject(
            "SELECT COALESCE(OCTET_LENGTH(t.request_snapshot),0)+COALESCE(OCTET_LENGTH(t.goal),0)+COALESCE(OCTET_LENGTH(t.summary),0)+COALESCE(OCTET_LENGTH(t.model_snapshot),0)+COALESCE(OCTET_LENGTH(t.tools_snapshot),0)+(SELECT OCTET_LENGTH(binding_snapshot)+COALESCE(OCTET_LENGTH(retained_digests),0) FROM task_todo_projection WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(usage_snapshot),0)),0) FROM model_dispatch WHERE tenant_id=t.tenant_id AND task_id=t.id) FROM task t WHERE tenant_id=? AND id=?",
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"));
    if (bytes + additional + 65536 > 2097152) throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
  }

  private void todoCommitFence(Lease lease) {
    if (db.update(
            "UPDATE task SET updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND current_attempt_id=? AND worker_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6) AND deadline>CURRENT_TIMESTAMP(6) AND cancel_requested_at IS NULL AND state='RUNNING'",
            lease.tenant(),
            lease.taskId(),
            lease.attemptId(),
            lease.worker(),
            lease.fence())
        != 1) throw new TaskFailure(409, "FENCE_REJECTED");
  }

  private void directoryMetadata(Map<String, Object> task, boolean closing) {
    // UTF-8 scalar values plus eight bytes per column and 32 per row conservatively
    // bound fixed metadata. Body copies are counted separately by todoStorage.
    long bytes = 0;
    var body =
        java.util.Set.of(
            "request_snapshot",
            "goal",
            "summary",
            "model_snapshot",
            "tools_snapshot",
            "binding_snapshot",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "retained_digests",
            "filesystem_receipt",
            "directory_cwd",
            "cwd",
            "base_cwd",
            "target_cwd",
            "canonical_paths_snapshot",
            "directory_evidence_snapshot");
    for (String table :
        List.of(
            "task",
            "task_directory",
            "invocation_path_binding",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "event")) {
      String key = table.equals("task") ? "id" : "task_id";
      for (var row :
          rows(
              "SELECT * FROM " + table + " WHERE tenant_id=? AND " + key + "=?",
              s(task, "tenant_id"),
              s(task, "id"))) {
        bytes += 32;
        for (var entry : row.entrySet())
          if (!body.contains(entry.getKey()))
            bytes +=
                8
                    + (entry.getValue() == null
                        ? 1
                        : io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                            entry.getValue().toString()));
      }
    }
    if (bytes > (closing ? 65536 : 61440)) throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
  }

  private void todoMetadata(Map<String, Object> task, boolean closing) {
    // UTF-8 scalar values plus eight bytes per column and 32 per row conservatively
    // bound fixed metadata. Body copies are counted separately by todoStorage.
    long bytes = 0;
    var body =
        java.util.Set.of(
            "request_snapshot",
            "goal",
            "summary",
            "model_snapshot",
            "tools_snapshot",
            "binding_snapshot",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "retained_digests");
    for (String table :
        List.of(
            "task",
            "task_todo_projection",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "reservation",
            "event")) {
      String key = table.equals("task") ? "id" : "task_id";
      for (var row :
          rows(
              "SELECT * FROM " + table + " WHERE tenant_id=? AND " + key + "=?",
              s(task, "tenant_id"),
              s(task, "id"))) {
        bytes += 32;
        for (var entry : row.entrySet())
          if (!body.contains(entry.getKey()))
            bytes +=
                8
                    + (entry.getValue() == null
                        ? 1
                        : io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                            entry.getValue().toString()));
      }
    }
    if (bytes > (closing ? 65536 : 61440)) throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
  }

  @Override
  public io.github.lovexiaopeng.jimu.plugin.api.TodoPort.Result replaceOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    // Session settings belong to this transaction's dedicated DriverManager connection.
    return leaseTx(
        lease,
        () ->
            db.execute(
                (org.springframework.jdbc.core.ConnectionCallback<
                        io.github.lovexiaopeng.jimu.plugin.api.TodoPort.Result>)
                    connection -> {
                      int remaining =
                          (int)
                              java.time.Duration.between(Instant.now(), lease.deadline())
                                  .toSeconds();
                      if (remaining < 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
                      int prior;
                      try (var statement = connection.createStatement();
                          var result =
                              statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                        result.next();
                        prior = result.getInt(1);
                      }
                      try (var statement = connection.createStatement()) {
                        statement.execute(
                            "SET SESSION innodb_lock_wait_timeout=" + Math.min(2, remaining));
                      }
                      try {
                        var task = fenced(lease, true);
                        if (!todoTask(task)) throw new TaskFailure(403, "TODO_NOT_AUTHORIZED");
                        var p = todoProjection(task);
                        var candidates =
                            rows(
                                "SELECT i.*,s.state AS step_state,s.record_version AS step_version,s.response_snapshot,s.response_digest,s.task_id AS step_task FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.tenant_id=? AND i.task_id=? AND i.step_id=? AND i.slot=? FOR UPDATE",
                                lease.tenant(),
                                lease.taskId(),
                                stepId,
                                call.slot());
                        if (candidates.size() != 1)
                          throw new TaskFailure(409, "INVOCATION_MISMATCH");
                        var i = candidates.getFirst();
                        if (!"todo_write".equals(call.tool())
                            || !call.tool().equals(s(i, "tool"))
                            || !call.providerId().equals(s(i, "provider_id"))
                            || !call.input().equals(s(i, "input_snapshot"))
                            || !Payloads.hash(s(i, "input_snapshot")).equals(s(i, "input_digest"))
                            || !lease.taskId().equals(s(i, "step_task"))
                            || !"RESPONDED".equals(s(i, "step_state"))
                            || !Payloads.hash(s(i, "response_snapshot"))
                                .equals(s(i, "response_digest")))
                          throw new TaskFailure(409, "INVOCATION_MISMATCH");
                        if (n(i, "record_version") != 1 || n(i, "step_version") != 1)
                          throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
                        var response =
                            Payloads.read(
                                s(i, "response_snapshot"),
                                io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent
                                    .Response.class);
                        if (response.version() != 2)
                          throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
                        boolean originalSlot = false;
                        for (int slot = 0; slot < response.calls().size(); slot++) {
                          var wire = response.calls().get(slot);
                          if (new Call(
                                  slot + ":" + wire.id(), wire.id(), wire.name(), wire.arguments())
                              .equals(call)) originalSlot = true;
                        }
                        if (!originalSlot) throw new TaskFailure(409, "INVOCATION_MISMATCH");
                        var pin = tools(task).pins().get("todo_write");
                        if (!version.equals(pin.version())
                            || !digest.equals(pin.digest())
                            || !version.equals(s(i, "plugin_version"))
                            || !digest.equals(s(i, "plugin_digest"))
                            || pin.installationEpoch() != n(i, "installation_epoch"))
                          throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
                        if (i.get("todo_expected_revision") == null
                            || n(i, "todo_expected_revision") < 0)
                          throw new TaskFailure(409, "TODO_BINDING_UNSUPPORTED");
                        var result = todos.input(s(i, "input_snapshot"));
                        String output = Payloads.json(result), invocation = s(i, "id");
                        if ("SUCCEEDED".equals(s(i, "state"))) {
                          todoResultSource(task, i);
                          if (!output.equals(s(i, "output_snapshot")))
                            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
                          todoCommitFence(lease);
                          return todos.output(s(i, "output_snapshot"));
                        }
                        if (!"PENDING".equals(s(i, "state"))
                            || i.get("todo_applied_revision") != null)
                          throw new TaskFailure(409, "INVOCATION_MISMATCH");
                        long expected = n(i, "todo_expected_revision");
                        if (n(p, "revision") != expected)
                          throw new TaskFailure(409, "TODO_REVISION_CONFLICT");
                        var budget = bucket(lease.tenant());
                        if (n(task, "tools_used") >= n(task, "max_tools")
                            || n(budget, "tool_used") >= n(budget, "tool_limit"))
                          throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
                        todos.output(output);
                        todoStorage(
                            task,
                            io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                                output));
                        db.update(
                            "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
                            lease.tenant(),
                            lease.taskId());
                        db.update(
                            "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?",
                            lease.tenant());
                        reserve(
                            lease.tenant(),
                            lease.taskId(),
                            invocation,
                            invocation,
                            "TOOL",
                            1,
                            "SETTLED",
                            "KNOWN");
                        db.update(
                            "UPDATE reservation SET actual=1 WHERE tenant_id=? AND id=?",
                            lease.tenant(),
                            invocation);
                        event(task, "intent:" + invocation, "tool.dispatched", null);
                        event(task, "todo-write:" + invocation, "todo.updated", null);
                        long seq =
                            db.queryForObject(
                                "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                                Long.class,
                                lease.tenant(),
                                lease.taskId(),
                                "todo-write:" + invocation);
                        if (db.update(
                                "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,todo_applied_revision=?,todo_committed_attempt_id=?,todo_committed_fence=?,todo_event_seq=?,todo_committed_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='PENDING' AND todo_applied_revision IS NULL",
                                output,
                                Payloads.hash(output),
                                expected + 1,
                                lease.attemptId(),
                                lease.fence(),
                                seq,
                                lease.tenant(),
                                invocation)
                            != 1) throw new TaskFailure(409, "INVOCATION_MISMATCH");
                        if (db.update(
                                "UPDATE task_todo_projection SET revision=revision+1,content_state='PRESENT',source_invocation_id=?,source_step_id=?,committed_attempt_id=?,committed_fence=?,updated_seq=?,pending_count=?,in_progress_count=?,completed_count=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND revision=? AND content_state<>'PURGED'",
                                invocation,
                                stepId,
                                lease.attemptId(),
                                lease.fence(),
                                seq,
                                result.counts().pending(),
                                result.counts().inProgress(),
                                result.counts().completed(),
                                Payloads.hash(output),
                                lease.tenant(),
                                lease.taskId(),
                                expected)
                            != 1) throw new TaskFailure(409, "TODO_REVISION_CONFLICT");
                        event(task, "result:" + invocation, "tool.completed", null);
                        todoCommitFence(lease);
                        return result;
                      } finally {
                        try (var statement = connection.createStatement()) {
                          statement.execute("SET SESSION innodb_lock_wait_timeout=" + prior);
                        }
                      }
                    }));
  }

  private void todoResultSource(Map<String, Object> task, Map<String, Object> i) {
    if (i.get("todo_applied_revision") == null
        || n(i, "todo_applied_revision") != n(i, "todo_expected_revision") + 1
        || !"SUCCEEDED".equals(s(i, "state"))
        || !"todo_write".equals(s(i, "tool"))
        || i.get("todo_committed_attempt_id") == null
        || i.get("todo_committed_fence") == null
        || i.get("todo_event_seq") == null
        || i.get("todo_committed_at") == null) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var attempt =
        rows(
            "SELECT id FROM task_attempt WHERE tenant_id=? AND task_id=? AND id=? AND fence=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(i, "todo_committed_attempt_id"),
            n(i, "todo_committed_fence"));
    if (attempt.size() != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!"PURGED".equals(s(i, "content_state"))
        && !Payloads.hash(s(i, "output_snapshot")).equals(s(i, "output_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  @Override
  public Map<String, Object> getTodos(Identity identity, String taskId) {
    try {
      UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, "NOT_FOUND");
    }
    return tx(
        () -> {
          var task = visible(identity, taskId);
          access(task);
          var r =
              one(
                  "SELECT t.next_event_seq,p.*,i.id AS invocation_id,i.task_id AS invocation_task,i.step_id AS invocation_step,i.attempt_id,i.provider_id,i.tool,i.state AS state,i.todo_expected_revision,i.todo_applied_revision,i.todo_committed_attempt_id,i.todo_committed_fence,i.todo_event_seq,i.todo_committed_at,i.output_snapshot,i.output_digest AS invocation_digest FROM task t LEFT JOIN task_todo_projection p ON p.tenant_id=t.tenant_id AND p.task_id=t.id LEFT JOIN invocation i ON i.tenant_id=p.tenant_id AND i.id=p.source_invocation_id WHERE t.tenant_id=? AND t.id=? AND t.owner_actor_id=?",
                  identity.tenant(),
                  taskId,
                  identity.actor());
          var view = new java.util.LinkedHashMap<String, Object>();
          view.put("version", 1);
          view.put("taskId", taskId);
          view.put("asOfSeq", n(r, "next_event_seq"));
          boolean absent = r.get("task_id") == null;
          view.put("revision", absent ? 0 : n(r, "revision"));
          view.put("state", absent ? "ABSENT" : s(r, "content_state"));
          view.put("todos", null);
          view.put("counts", null);
          view.put("source", null);
          view.put("retainUntil", absent ? null : time(r, "retain_until").toString());
          if (!absent && n(r, "revision") > 0) {
            if (!taskId.equals(s(r, "invocation_task"))
                || !s(r, "source_step_id").equals(s(r, "invocation_step"))
                || n(r, "revision") != n(r, "todo_applied_revision")
                || !s(r, "committed_attempt_id").equals(s(r, "todo_committed_attempt_id"))
                || n(r, "committed_fence") != n(r, "todo_committed_fence")
                || n(r, "updated_seq") != n(r, "todo_event_seq")
                || !s(r, "output_digest").equals(s(r, "invocation_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            todoResultSource(task, r);
            var counts =
                new io.github.lovexiaopeng.jimu.plugin.api.TodoPort.Counts(
                    (int) n(r, "pending_count"),
                    (int) n(r, "in_progress_count"),
                    (int) n(r, "completed_count"));
            if ("PRESENT".equals(s(r, "content_state"))) {
              var output = todos.output(s(r, "output_snapshot"));
              if (!counts.equals(output.counts())) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
              view.put("todos", output.todos());
            }
            view.put("counts", counts);
            view.put(
                "source",
                Map.of(
                    "invocationId",
                    s(r, "source_invocation_id"),
                    "checkpointId",
                    s(r, "source_step_id"),
                    "providerCallId",
                    s(r, "provider_id"),
                    "createdAttemptId",
                    s(r, "attempt_id"),
                    "committedAttemptId",
                    s(r, "committed_attempt_id"),
                    "fence",
                    n(r, "committed_fence"),
                    "eventId",
                    taskId + ":" + n(r, "updated_seq"),
                    "outputSha256",
                    s(r, "output_digest"),
                    "updatedAt",
                    time(r, "todo_committed_at").toString()));
          }
          todos.view(view);
          return view;
        });
  }

  public void questionsEnabled(boolean enabled) {
    questionsEnabled = enabled;
  }

  private boolean directoryAvailable(Identity identity) {
    if (!directoryEnabled) return false;
    try {
      models.requireDirectory(models.selected(identity.tenant()));
      var installed =
          one(
              "SELECT plugin_version,plugin_digest FROM installation WHERE tenant_id=? AND plugin_id='working_directory'",
              identity.tenant());
      return directories.descriptor().version().equals(s(installed, "plugin_version"))
          && directories.descriptor().outputSha256().equals(s(installed, "plugin_digest"));
    } catch (TaskFailure e) {
      return false;
    }
  }

  private boolean todoAvailable(Identity identity) {
    if (!todosEnabled) return false;
    try {
      models.requireTodo(models.selected(identity.tenant()));
      var installed =
          one(
              "SELECT plugin_version,plugin_digest FROM installation WHERE tenant_id=? AND plugin_id='todo_write'",
              identity.tenant());
      return todos.descriptor().version().equals(s(installed, "plugin_version"))
          && todos.descriptor().outputSha256().equals(s(installed, "plugin_digest"))
          && models.businessPolicy() == null
          && models.scopeRequirement() == null
          && models.evaluationRunId() == null;
    } catch (TaskFailure e) {
      return false;
    }
  }

  private void rejectTodoBypass(Lease lease, String invocation) {
    if (!rows(
            "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='session_event_read'",
            lease.tenant(),
            lease.taskId(),
            invocation)
        .isEmpty()) throw new TaskFailure(409, "SESSION_READ_COMMIT_REQUIRED");
    if (!rows(
            "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='working_directory'",
            lease.tenant(),
            lease.taskId(),
            invocation)
        .isEmpty()) throw new TaskFailure(409, "DIRECTORY_COMMIT_REQUIRED");
    if (!rows(
            "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='todo_write'",
            lease.tenant(),
            lease.taskId(),
            invocation)
        .isEmpty()) throw new TaskFailure(409, "TODO_COMMIT_REQUIRED");
  }

  private void releaseQuestionTask(Map<String, Object> task) {
    if (!questionTask(task)) return;
    int count =
        db.update(
            "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
            s(task, "tenant_id"),
            s(task, "id") + ":question-task");
    if (count == 1)
      db.update(
          "UPDATE tenant_budget SET question_tasks_held=question_tasks_held-1 WHERE tenant_id=?",
          s(task, "tenant_id"));
  }

  private void cleanupTodos() {
    // Retained UNKNOWN facts or old writers must not occupy the entire bounded batch.
    // Candidate filters do not replace the checks below under the task lock.
    for (var candidate :
        rows(
            """
            SELECT p.tenant_id,p.task_id FROM task_todo_projection p
            JOIN task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id
            WHERE p.content_state<>'PURGED' AND p.retain_until<=CURRENT_TIMESTAMP(6)
              AND t.state IN ('SUCCEEDED','FAILED','CANCELLED')
              AND NOT EXISTS (SELECT 1 FROM native_search_run n
                WHERE n.tenant_id=p.tenant_id AND n.task_id=p.task_id AND n.phase<>'STOPPED')
              AND NOT EXISTS (SELECT 1 FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id
                WHERE a.tenant_id=p.tenant_id AND a.task_id=p.task_id
                  AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE))
              AND NOT EXISTS (SELECT 1 FROM reservation r
                WHERE r.tenant_id=p.tenant_id AND r.task_id=p.task_id
                  AND (r.state='HELD_UNKNOWN' OR r.usage_quality='UNKNOWN'
                    OR (r.state='RESERVED' AND r.category<>'TODO_STORAGE')))
              AND NOT EXISTS (SELECT 1 FROM invocation i
                WHERE i.tenant_id=p.tenant_id AND i.task_id=p.task_id
                  AND i.state IN ('RUNNING','OUTCOME_UNKNOWN'))
              AND NOT EXISTS (SELECT 1 FROM model_dispatch d
                WHERE d.tenant_id=p.tenant_id AND d.task_id=p.task_id AND d.state='DISPATCHED')
            ORDER BY p.retain_until,p.tenant_id,p.task_id LIMIT 32
            """)) {
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id"), taskId = s(candidate, "task_id");
            bucket(tenant);
            var task = locked(tenant, taskId);
            var p = todoProjection(task);
            if ("PURGED".equals(s(p, "content_state"))
                || time(p, "retain_until").isAfter(questionNow())
                || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state"))
                || !writersQuiescent(task)) return null;
            if (!rows(
                        "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND (state='HELD_UNKNOWN' OR usage_quality='UNKNOWN' OR (state='RESERVED' AND category<>'TODO_STORAGE')) LIMIT 1",
                        tenant,
                        taskId)
                    .isEmpty()
                || !rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN') LIMIT 1",
                        tenant,
                        taskId)
                    .isEmpty()
                || !rows(
                        "SELECT id FROM model_dispatch WHERE tenant_id=? AND task_id=? AND state='DISPATCHED' LIMIT 1",
                        tenant,
                        taskId)
                    .isEmpty()) return null;
            var retained = new java.util.LinkedHashMap<String, Object>();
            for (String field : List.of("request_snapshot", "goal", "summary"))
              retainQuestionBody(retained, "task:" + field, s(task, field));
            String tombstone = "{\"version\":1,\"state\":\"TODO_PURGED\"}";
            for (var step :
                rows("SELECT * FROM step WHERE tenant_id=? AND task_id=?", tenant, taskId)) {
              for (String field : List.of("request_snapshot", "response_snapshot"))
                retainQuestionBody(retained, "step:" + s(step, "id") + ":" + field, s(step, field));
              db.update(
                  "UPDATE step SET request_snapshot=?,response_snapshot=IF(response_snapshot IS NULL,NULL,?),request_cleanup_state='TODO_PURGED',retained_request_digest=request_digest WHERE tenant_id=? AND id=?",
                  tombstone,
                  tombstone,
                  tenant,
                  s(step, "id"));
            }
            for (var invocation :
                rows("SELECT * FROM invocation WHERE tenant_id=? AND task_id=?", tenant, taskId)) {
              for (String field : List.of("input_snapshot", "output_snapshot"))
                retainQuestionBody(
                    retained,
                    "invocation:" + s(invocation, "id") + ":" + field,
                    s(invocation, field));
              db.update(
                  "UPDATE invocation SET input_snapshot=?,output_snapshot=IF(output_snapshot IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                  tombstone,
                  tombstone,
                  tenant,
                  s(invocation, "id"));
            }
            for (var dispatch :
                rows(
                    "SELECT * FROM model_dispatch WHERE tenant_id=? AND task_id=?",
                    tenant,
                    taskId)) {
              retainQuestionBody(
                  retained,
                  "dispatch:" + s(dispatch, "id") + ":usage_snapshot",
                  s(dispatch, "usage_snapshot"));
              db.update(
                  "UPDATE model_dispatch SET usage_snapshot=IF(usage_snapshot IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                  tombstone,
                  tenant,
                  s(dispatch, "id"));
            }
            String digests = Payloads.json(retained);
            if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(digests)
                > 65536) throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
            db.update(
                "UPDATE task SET request_snapshot=?,goal=?,summary=IF(summary IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                tombstone,
                tombstone,
                tombstone,
                tenant,
                taskId);
            db.update(
                "UPDATE task_todo_projection SET content_state='PURGED',retained_digests=? WHERE tenant_id=? AND task_id=?",
                digests,
                tenant,
                taskId);
            event(task, "todo-purged", "todo.content_deleted", null);
            if (db.update(
                    "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
                    tenant,
                    taskId + ":todo-storage")
                != 1) throw new TaskFailure(409, "RESERVATION_STATE");
            db.update(
                "UPDATE tenant_budget SET todo_storage_held=todo_storage_held-2097152 WHERE tenant_id=?",
                tenant);
            return null;
          });
    }
  }

  private void cleanupQuestions() {
    for (var candidate :
        rows(
            "SELECT tenant_id,id FROM task WHERE question_cleanup_state='RETAINED' AND state IN ('SUCCEEDED','FAILED','CANCELLED') AND created_at<=CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY ORDER BY created_at,id LIMIT 32")) {
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id"), taskId = s(candidate, "id");
            bucket(tenant);
            var task = locked(tenant, taskId);
            if (!"RETAINED".equals(s(task, "question_cleanup_state"))
                || !view(task).terminal()
                || !writersQuiescent(task)) return null;
            if (!rows(
                        "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND (state='HELD_UNKNOWN' OR usage_quality='UNKNOWN') LIMIT 1",
                        tenant,
                        taskId)
                    .isEmpty()
                || !rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state='OUTCOME_UNKNOWN' LIMIT 1",
                        tenant,
                        taskId)
                    .isEmpty()) return null;
            var retained = new java.util.LinkedHashMap<String, Object>();
            for (String field : List.of("request_snapshot", "goal", "summary"))
              retainQuestionBody(retained, "task:" + field, s(task, field));
            String tombstone = "{\"version\":1,\"state\":\"QUESTION_PURGED\"}";
            for (var step :
                rows("SELECT * FROM step WHERE tenant_id=? AND task_id=?", tenant, taskId)) {
              for (String field : List.of("request_snapshot", "response_snapshot"))
                retainQuestionBody(retained, "step:" + s(step, "id") + ":" + field, s(step, field));
              db.update(
                  "UPDATE step SET request_snapshot=?,response_snapshot=IF(response_snapshot IS NULL,NULL,?),request_cleanup_state='QUESTION_PURGED',retained_request_digest=request_digest WHERE tenant_id=? AND id=?",
                  tombstone,
                  tombstone,
                  tenant,
                  s(step, "id"));
            }
            for (var invocation :
                rows("SELECT * FROM invocation WHERE tenant_id=? AND task_id=?", tenant, taskId)) {
              for (String field : List.of("input_snapshot", "output_snapshot"))
                retainQuestionBody(
                    retained,
                    "invocation:" + s(invocation, "id") + ":" + field,
                    s(invocation, field));
              db.update(
                  "UPDATE invocation SET input_snapshot=?,output_snapshot=IF(output_snapshot IS NULL,NULL,?) WHERE tenant_id=? AND id=?",
                  tombstone,
                  tombstone,
                  tenant,
                  s(invocation, "id"));
            }
            for (var q :
                rows(
                    "SELECT * FROM user_question WHERE tenant_id=? AND task_id=?",
                    tenant,
                    taskId)) {
              var metadata = new java.util.LinkedHashMap<String, Object>();
              for (String field :
                  List.of("request_snapshot", "answer_snapshot", "receipt_snapshot"))
                retainQuestionBody(metadata, field, s(q, field));
              db.update(
                  "UPDATE user_question SET request_snapshot=?,answer_snapshot=IF(answer_snapshot IS NULL,NULL,?),receipt_snapshot=IF(receipt_snapshot IS NULL,NULL,?),retained_digests=? WHERE tenant_id=? AND id=?",
                  tombstone,
                  tombstone,
                  tombstone,
                  Payloads.json(metadata),
                  tenant,
                  s(q, "id"));
            }
            db.update(
                "UPDATE task SET request_snapshot=?,goal=?,summary=IF(summary IS NULL,NULL,?),question_cleanup_state='PURGED',question_retained_digests=? WHERE tenant_id=? AND id=?",
                tombstone,
                tombstone,
                tombstone,
                Payloads.json(retained),
                tenant,
                taskId);
            if (db.update(
                    "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
                    tenant,
                    taskId + ":question-storage")
                == 1)
              db.update(
                  "UPDATE tenant_budget SET storage_held=storage_held-2097152 WHERE tenant_id=?",
                  tenant);
            event(task, "question-purged", "question.content_deleted", null);
            return null;
          });
    }
  }

  private void retainQuestionBody(Map<String, Object> metadata, String key, String body) {
    if (body != null)
      metadata.put(
          key,
          Map.of(
              "sha256",
              Payloads.hash(body),
              "bytes",
              body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
  }

  private boolean questionTask(Map<String, Object> task) {
    return caps(task).contains("ask_user_question");
  }

  private void questionAccess(Map<String, Object> task) {
    var pin = tools(task).pins().get("ask_user_question");
    var contract = questions.contract(pin.version());
    var installed =
        one(
            "SELECT * FROM installation WHERE tenant_id=? AND plugin_id='ask_user_question'",
            s(task, "tenant_id"));
    if (!pin.version().equals(s(installed, "plugin_version"))
        || !pin.digest().equals(s(installed, "plugin_digest"))
        || !pin.digest().equals(contract.descriptor().outputSha256())
        || pin.installationEpoch() != n(installed, "epoch"))
      throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    if ("PURGED".equals(s(task, "question_cleanup_state")))
      throw new TaskFailure(410, "QUESTION_CONTENT_EXPIRED");
    String binding = s(task, "question_binding");
    if (binding == null
        || !Payloads.hash(binding).equals(s(task, "question_binding_digest"))
        || !questions.binding(pin.version()).equals(binding))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  private Instant questionNow() {
    return db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.time.LocalDateTime.class)
        .toInstant(java.time.ZoneOffset.UTC);
  }

  private Map<String, Object> questionVisible(Identity identity, String taskId) {
    identity(identity);
    var found =
        rows(
            "SELECT id FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=?",
            identity.tenant(),
            taskId,
            identity.actor());
    if (found.isEmpty()) throw new TaskFailure(404, "TASK_NOT_FOUND");
    var task = locked(identity.tenant(), taskId);
    access(task);
    return task;
  }

  @Override
  public Map<String, Object> listQuestions(Identity identity, String taskId) {
    return tx(
        () -> {
          bucket(identity.tenant());
          var task = questionVisible(identity, taskId);
          if (!questionTask(task)) return Map.of("version", 1, "items", List.of());
          Instant now = questionNow();
          var items = new java.util.ArrayList<Map<String, Object>>();
          for (var q :
              rows(
                  "SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? ORDER BY q.ordinal",
                  identity.tenant(),
                  taskId)) {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("requestId", s(q, "id"));
            item.put("questionVersion", n(q, "question_version"));
            item.put("revision", n(q, "revision"));
            item.put("invocationId", s(q, "invocation_id"));
            item.put("callId", s(q, "provider_id"));
            item.put("mode", s(q, "mode"));
            item.put("phase", s(q, "phase"));
            item.put("questions", questionInput(q, task).questions());
            item.put(
                "foregroundDueAt",
                q.get("foreground_due_at") == null
                    ? null
                    : time(q, "foreground_due_at").toString());
            item.put("answerExpiresAt", time(q, "answer_expires_at").toString());
            item.put(
                "answerable",
                questionsEnabled
                    && !view(task).terminal()
                    && task.get("cancel_requested_at") == null
                    && now.isBefore(time(q, "answer_expires_at"))
                    && List.of("OPEN", "CONTINUED").contains(s(q, "phase")));
            if (q.get("answer_id") != null) {
              item.put("answerId", s(q, "answer_id"));
              item.put("answeredBy", s(q, "answered_actor_id"));
              item.put("answeredAt", time(q, "answered_at").toString());
              item.put(
                  "answers",
                  Payloads.read(
                      s(q, "answer_snapshot"), com.fasterxml.jackson.databind.JsonNode.class));
              item.put("deliveryState", s(q, "delivery_state"));
            }
            if (q.get("close_code") != null) item.put("closeCode", s(q, "close_code"));
            items.add(item);
          }
          var result = Map.<String, Object>of("version", 1, "items", items);
          if (Payloads.json(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > 524288) throw new TaskFailure(409, "QUESTION_RESPONSE_TOO_LARGE");
          return result;
        });
  }

  private io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin.Input
      questionInput(Map<String, Object> q, Map<String, Object> task) {
    if (n(q, "record_version") != 1
        || n(q, "question_version") != 1
        || !s(q, "binding_digest").equals(s(task, "question_binding_digest"))
        || !Payloads.hash(s(q, "request_snapshot")).equals(s(q, "request_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return questions.input(
        tools(task).pins().get("ask_user_question").version(), s(q, "request_snapshot"));
  }

  @Override
  public void authorizeAnswer(Identity identity, String taskId, String requestId) {
    tx(
        () -> {
          bucket(identity.tenant());
          questionVisible(identity, taskId);
          if (rows(
                  "SELECT id FROM user_question WHERE tenant_id=? AND task_id=? AND id=?",
                  identity.tenant(),
                  taskId,
                  requestId)
              .isEmpty()) throw new TaskFailure(404, "QUESTION_NOT_FOUND");
          return null;
        });
  }

  @Override
  public Submission answer(
      Identity identity, String taskId, String requestId, String key, Command command) {
    var outcome =
        tx(
            () -> {
              bucket(identity.tenant());
              var task = questionVisible(identity, taskId);
              var found =
                  rows(
                      "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
                      identity.tenant(),
                      taskId,
                      requestId);
              if (found.isEmpty()) throw new TaskFailure(404, "QUESTION_NOT_FOUND");
              var q = found.getFirst();
              var previous =
                  rows(
                      "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND owner_actor_id=? AND answer_key=?",
                      identity.tenant(),
                      taskId,
                      identity.actor(),
                      key);
              if (!previous.isEmpty()) {
                var old = previous.getFirst();
                String requestDigest;
                try {
                  requestDigest =
                      questionAnswerDigest(
                          requestId,
                          command,
                          questions.canonicalAnswers(questionInput(q, task).questions(), command));
                } catch (TaskFailure invalid) {
                  throw new TaskFailure(409, "IDEMPOTENCY_CONFLICT");
                }
                if (!requestDigest.equals(s(old, "answer_request_digest")))
                  throw new TaskFailure(409, "IDEMPOTENCY_CONFLICT");
                return new AdmissionResult<Submission>(new Submission(true, receipt(old)), null);
              }
              Instant now = questionNow();
              if (!now.isBefore(time(q, "answer_expires_at"))) {
                closeQuestions(task, "DEADLINE_EXCEEDED");
                if (!view(task).terminal()) terminal(task, "FAILED", "DEADLINE_EXCEEDED");
                return new AdmissionResult<Submission>(
                    null, new TaskFailure(410, "QUESTION_EXPIRED"));
              }
              if (view(task).terminal()
                  || task.get("cancel_requested_at") != null
                  || "CLOSED".equals(s(q, "phase"))) throw new TaskFailure(409, "QUESTION_CLOSED");
              if (!questionsEnabled) throw new TaskFailure(403, "QUESTION_FEATURE_DISABLED");
              if (q.get("foreground_due_at") != null
                  && !now.isBefore(time(q, "foreground_due_at"))
                  && "OPEN".equals(s(q, "phase"))) {
                continueQuestion(task, q);
                q =
                    one(
                        "SELECT * FROM user_question WHERE tenant_id=? AND id=?",
                        identity.tenant(),
                        requestId);
              }
              if (q.get("answer_id") != null)
                return new AdmissionResult<Submission>(
                    null, new TaskFailure(409, "QUESTION_ALREADY_ANSWERED"));
              if (command.questionVersion() != 1)
                return new AdmissionResult<Submission>(
                    null, new TaskFailure(409, "QUESTION_VERSION_UNSUPPORTED"));
              if (command.expectedRevision() != n(q, "revision"))
                return new AdmissionResult<Submission>(
                    null, new TaskFailure(409, "QUESTION_REVISION_CONFLICT"));
              java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer>
                  answers;
              try {
                answers = questions.canonicalAnswers(questionInput(q, task).questions(), command);
              } catch (TaskFailure invalid) {
                return new AdmissionResult<Submission>(null, invalid);
              }
              String answer = Payloads.json(answers);
              String requestDigest = questionAnswerDigest(requestId, command, answers);
              String answerId = id();
              boolean foreground = "OPEN".equals(s(q, "phase"));
              int seq =
                  db.queryForObject(
                      "SELECT COALESCE(MAX(accepted_seq),0)+1 FROM user_question WHERE tenant_id=? AND task_id=?",
                      Integer.class,
                      identity.tenant(),
                      taskId);
              var receipt =
                  Map.<String, Object>of(
                      "version",
                      1,
                      "requestId",
                      requestId,
                      "answerId",
                      answerId,
                      "questionVersion",
                      1,
                      "acceptedRevision",
                      n(q, "revision") + 1,
                      "answeredBy",
                      identity.actor(),
                      "answeredAt",
                      now.toString(),
                      "delivery",
                      foreground ? "TOOL_RESULT" : "USER_MESSAGE");
              try {
                questionStorage(
                    task,
                    answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + Payloads.json(receipt)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length
                        + 16384);
              } catch (TaskFailure invalid) {
                return new AdmissionResult<Submission>(null, invalid);
              }
              if (db.update(
                      "UPDATE user_question SET phase='ANSWERED',revision=revision+1,answer_id=?,answered_actor_id=?,answered_at=?,accepted_seq=?,answer_snapshot=?,answer_digest=?,answer_key=?,answer_request_digest=?,answer_source_revision=?,receipt_snapshot=?,delivery_state=? WHERE tenant_id=? AND id=? AND revision=? AND answer_id IS NULL",
                      answerId,
                      identity.actor(),
                      java.sql.Timestamp.from(now),
                      seq,
                      answer,
                      Payloads.hash(answer),
                      key,
                      requestDigest,
                      command.expectedRevision(),
                      Payloads.json(receipt),
                      foreground ? "CHECKPOINTED" : "PENDING",
                      identity.tenant(),
                      requestId,
                      n(q, "revision"))
                  != 1) throw new TaskFailure(409, "QUESTION_REVISION_CONFLICT");
              if (foreground) {
                String output =
                    Payloads.json(
                        new io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.AnswerBatch(
                            answers, null, null, null));
                questionToolResult(task, q, output);
                questionResume(task, requestId);
              }
              event(task, "question-answer:" + answerId, "question.answered", null);
              return new AdmissionResult<Submission>(new Submission(false, receipt), null);
            });
    if (outcome.rejection() != null) throw outcome.rejection();
    return outcome.value();
  }

  private String questionAnswerDigest(
      String requestId,
      Command command,
      java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer> answers) {
    return Payloads.hash(
        Payloads.json(
            new QuestionAnswerRequest(
                requestId, command.questionVersion(), command.expectedRevision(), answers)));
  }

  private record QuestionAnswerRequest(
      String requestId,
      int questionVersion,
      int expectedRevision,
      java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer> answers) {}

  private record LateQuestionReply(
      int version,
      String kind,
      String tool,
      String requestId,
      String callId,
      java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Question> questions,
      com.fasterxml.jackson.databind.JsonNode answers) {}

  @SuppressWarnings("unchecked")
  private Map<String, Object> receipt(Map<String, Object> q) {
    return Payloads.read(s(q, "receipt_snapshot"), Map.class);
  }

  @Override
  public OpenResult openOrResult(
      Lease lease, String step, Call call, String version, String digest) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var budget = bucket(lease.tenant());
          if (!questionTask(task) || !questionsEnabled)
            throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
          var input = questions.input(version, call.input());
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=? FOR UPDATE",
                  lease.tenant(),
                  lease.taskId(),
                  step,
                  call.slot());
          if (!call.tool().equals("ask_user_question")
              || !call.tool().equals(s(invocation, "tool"))
              || !call.providerId().equals(s(invocation, "provider_id"))
              || !Payloads.hash(call.input()).equals(s(invocation, "input_digest"))
              || !version.equals(s(invocation, "plugin_version"))
              || !digest.equals(s(invocation, "plugin_digest")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var pin = tools(task).pins().get("ask_user_question");
          if (!pin.version().equals(version)
              || !pin.digest().equals(digest)
              || pin.installationEpoch() != n(invocation, "installation_epoch"))
            throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          String invocationId = s(invocation, "id");
          if ("SUCCEEDED".equals(s(invocation, "state"))) {
            if (!Payloads.hash(s(invocation, "output_snapshot"))
                .equals(s(invocation, "output_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            questions.output(version, s(invocation, "output_snapshot"));
            return new OpenResult(
                false,
                Payloads.read(
                    s(invocation, "output_snapshot"),
                    io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.AnswerBatch.class));
          }
          if (!"PENDING".equals(s(invocation, "state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          int ordinal =
              db.queryForObject(
                  "SELECT COUNT(*)+1 FROM user_question WHERE tenant_id=? AND task_id=?",
                  Integer.class,
                  lease.tenant(),
                  lease.taskId());
          if (ordinal > 8) throw new TaskFailure(429, "QUESTION_BATCH_LIMIT");
          if (task.get("waiting_question_id") != null)
            throw new TaskFailure(409, "QUESTION_ALREADY_WAITING");
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          questionStorage(task, 16384);
          String requestId = id();
          Integer timeout =
              version.equals("0.1.0") ? null : input.timeout() == null ? 120 : input.timeout();
          db.update(
              "INSERT INTO user_question(tenant_id,id,task_id,owner_actor_id,invocation_id,ordinal,binding_digest,mode,effective_timeout,phase,request_snapshot,request_digest,foreground_due_at,answer_expires_at,retain_until) VALUES(?,?,?,?,?,?,?,?,?,'OPEN',?,?,IF(? IS NULL OR ?=-1,NULL,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))),?,?)",
              lease.tenant(),
              requestId,
              lease.taskId(),
              lease.actor(),
              invocationId,
              ordinal,
              s(task, "question_binding_digest"),
              questions.mode(version),
              timeout,
              call.input(),
              Payloads.hash(call.input()),
              timeout,
              timeout,
              timeout,
              task.get("deadline"),
              java.sql.Timestamp.from(time(task, "created_at").plusSeconds(604800)));
          db.update(
              "UPDATE task SET tools_used=tools_used+1,waiting_question_id=? WHERE tenant_id=? AND id=?",
              requestId,
              lease.tenant(),
              lease.taskId());
          db.update(
              "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "RESERVED",
              "KNOWN");
          if (db.update(
                  "UPDATE invocation SET state='WAITING_USER' WHERE tenant_id=? AND id=? AND state='PENDING'",
                  lease.tenant(),
                  invocationId)
              != 1) throw new TaskFailure(409, "INVOCATION_STATE");
          questionPause(task, lease, "WAITING_USER", null);
          event(task, "question-created:" + requestId, "question.created", null);
          event(task, "question-wait:" + requestId, "task.waiting_user", null);
          return new OpenResult(true, null);
        });
  }

  private void questionPause(
      Map<String, Object> task, Lease lease, String state, String continuation) {
    if (db.update(
            "UPDATE task_attempt SET state='PAUSED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND fence=? AND state='ACTIVE'",
            lease.tenant(),
            lease.attemptId(),
            lease.fence())
        != 1) throw new TaskFailure(409, "FENCE_REJECTED");
    if (db.update(
            "UPDATE task SET state=?,question_continuation_step_id=?,fence=fence+1,current_attempt_id=NULL,worker_id=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND fence=?",
            state,
            continuation,
            lease.tenant(),
            lease.taskId(),
            lease.fence())
        != 1) throw new TaskFailure(409, "FENCE_REJECTED");
  }

  private void questionToolResult(Map<String, Object> task, Map<String, Object> q, String output) {
    String tenant = s(task, "tenant_id"), invocation = s(q, "invocation_id");
    questions.output(tools(task).pins().get("ask_user_question").version(), output);
    if (db.update(
            "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND id=? AND state='WAITING_USER'",
            output,
            Payloads.hash(output),
            tenant,
            s(task, "id"),
            invocation)
        != 1) throw new TaskFailure(409, "INVOCATION_STATE");
    if (db.update(
            "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=? AND category='TOOL' AND state='RESERVED'",
            tenant,
            invocation)
        != 1) throw new TaskFailure(409, "RESERVATION_STATE");
    event(task, "tool:" + invocation, "tool.completed", null);
  }

  private void questionResume(Map<String, Object> task, String requestId) {
    if (db.update(
            "UPDATE task SET state='RESUME_READY',waiting_question_id=NULL WHERE tenant_id=? AND id=? AND state='WAITING_USER' AND waiting_question_id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            requestId)
        != 1) throw new TaskFailure(409, "QUESTION_BINDING_MISMATCH");
    event(task, "question-resume:" + requestId, "task.resumable", null);
  }

  private void continueQuestion(Map<String, Object> task, Map<String, Object> q) {
    var invocation =
        one(
            "SELECT provider_id FROM invocation WHERE tenant_id=? AND id=? AND task_id=?",
            s(task, "tenant_id"),
            s(q, "invocation_id"),
            s(task, "id"));
    String output =
        questions.pending(
            tools(task).pins().get("ask_user_question").version(), s(invocation, "provider_id"));
    questionToolResult(task, q, output);
    if (db.update(
            "UPDATE user_question SET phase='CONTINUED',revision=revision+1 WHERE tenant_id=? AND id=? AND phase='OPEN' AND revision=?",
            s(task, "tenant_id"),
            s(q, "id"),
            n(q, "revision"))
        != 1) throw new TaskFailure(409, "QUESTION_REVISION_CONFLICT");
    questionResume(task, s(q, "id"));
    event(task, "question-continued:" + s(q, "id"), "question.continued", null);
  }

  private void closeQuestions(Map<String, Object> task, String code) {
    if (!questionTask(task)) return;
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    for (var q :
        rows(
            "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND phase IN ('OPEN','CONTINUED') FOR UPDATE",
            tenant,
            taskId)) {
      db.update(
          "UPDATE user_question SET phase='CLOSED',revision=revision+1,close_code=? WHERE tenant_id=? AND id=?",
          code,
          tenant,
          s(q, "id"));
      db.update(
          "UPDATE invocation SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='WAITING_USER'",
          code.equals("CANCELLED") ? "CANCELLED" : "FAILED",
          tenant,
          s(q, "invocation_id"));
      db.update(
          "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=? AND category='TOOL' AND state='RESERVED'",
          tenant,
          s(q, "invocation_id"));
      event(task, "question-closed:" + s(q, "id"), "question.closed", code);
    }
    db.update(
        "UPDATE user_question SET delivery_state='DISCARDED',close_code=? WHERE tenant_id=? AND task_id=? AND delivery_state='PENDING'",
        code,
        tenant,
        taskId);
    db.update(
        "UPDATE task SET waiting_question_id=NULL,question_continuation_step_id=NULL WHERE tenant_id=? AND id=?",
        tenant,
        taskId);
  }

  @Override
  public List<String> pendingQuestionMessages(Lease lease) {
    return leaseTx(lease, () -> questionMessages(fenced(lease, true)).values().stream().toList());
  }

  private Map<String, String> questionMessages(Map<String, Object> task) {
    var result = new java.util.LinkedHashMap<String, String>();
    if (!questionTask(task)) return result;
    for (var q :
        rows(
            "SELECT q.*,i.provider_id FROM user_question q JOIN invocation i ON i.tenant_id=q.tenant_id AND i.id=q.invocation_id WHERE q.tenant_id=? AND q.task_id=? AND q.delivery_state='PENDING' ORDER BY q.accepted_seq",
            s(task, "tenant_id"),
            s(task, "id"))) {
      if (!Payloads.hash(s(q, "answer_snapshot")).equals(s(q, "answer_digest")))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      result.put(
          s(q, "id"),
          Payloads.json(
              new LateQuestionReply(
                  1,
                  "answer_to_pending_question",
                  "ask_user_question",
                  s(q, "id"),
                  s(q, "provider_id"),
                  questionInput(q, task).questions(),
                  Payloads.read(
                      s(q, "answer_snapshot"), com.fasterxml.jackson.databind.JsonNode.class))));
    }
    return result;
  }

  @Override
  public boolean questionContinuation(Lease lease) {
    return leaseTx(lease, () -> fenced(lease, true).get("question_continuation_step_id") != null);
  }

  private void questionStorage(Map<String, Object> task, long additional) {
    if (!questionTask(task)) return;
    long used =
        db.queryForObject(
            "SELECT COALESCE(OCTET_LENGTH(request_snapshot),0)+COALESCE(OCTET_LENGTH(goal),0)+COALESCE(OCTET_LENGTH(summary),0)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(answer_snapshot),0)+COALESCE(OCTET_LENGTH(receipt_snapshot),0)),0) FROM user_question WHERE tenant_id=t.tenant_id AND task_id=t.id) FROM task t WHERE tenant_id=? AND id=?",
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"));
    if (used + additional > 2097152) throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
  }

  private void maintainQuestions() {
    for (var candidate :
        rows(
            "SELECT t.tenant_id,t.id FROM task t LEFT JOIN actor_access a ON a.tenant_id=t.tenant_id AND a.actor_id=t.owner_actor_id LEFT JOIN installation i ON i.tenant_id=t.tenant_id AND i.plugin_id='ask_user_question' WHERE t.question_binding IS NOT NULL AND t.state IN ('WAITING_USER','RESUME_READY') AND (?=FALSE OR t.deadline<=CURRENT_TIMESTAMP(6) OR t.cancel_requested_at IS NOT NULL OR a.enabled=FALSE OR i.plugin_id IS NULL OR i.enabled=FALSE OR i.epoch<>t.installation_epoch OR i.plugin_version<>JSON_UNQUOTE(JSON_EXTRACT(t.tools_snapshot,'$.pins.ask_user_question.version')) OR i.plugin_digest<>JSON_UNQUOTE(JSON_EXTRACT(t.tools_snapshot,'$.pins.ask_user_question.digest')) OR EXISTS(SELECT 1 FROM user_question q WHERE q.tenant_id=t.tenant_id AND q.task_id=t.id AND q.phase='OPEN' AND q.foreground_due_at<=CURRENT_TIMESTAMP(6))) ORDER BY t.deadline,t.id LIMIT 64",
            questionsEnabled)) {
      tx(
          () -> {
            bucket(s(candidate, "tenant_id"));
            var task = locked(s(candidate, "tenant_id"), s(candidate, "id"));
            if (!List.of("WAITING_USER", "RESUME_READY").contains(s(task, "state"))) return null;
            String rejection = null;
            if (task.get("cancel_requested_at") != null) rejection = "CANCELLED";
            else if (n(task, "within_deadline") != 1) rejection = "DEADLINE_EXCEEDED";
            else if (!questionsEnabled) rejection = "QUESTION_FEATURE_DISABLED";
            else
              try {
                access(task);
              } catch (TaskFailure error) {
                rejection = error.code();
              }
            if (rejection != null) {
              closeQuestions(task, rejection);
              terminal(task, rejection.equals("CANCELLED") ? "CANCELLED" : "FAILED", rejection);
              return null;
            }
            for (var q :
                rows(
                    "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND phase='OPEN' AND foreground_due_at<=CURRENT_TIMESTAMP(6) FOR UPDATE",
                    s(task, "tenant_id"),
                    s(task, "id"))) continueQuestion(task, q);
            settleStoppedWriters(locked(s(task, "tenant_id"), s(task, "id")));
            return null;
          });
    }
  }

  public void imageStorage(ArtifactStore store) {
    imageStore = store;
  }

  public void mcpSettings(io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings settings) {
    mcp.configure(settings);
  }

  @Override
  public Binding mcpBinding(Lease lease) {
    return leaseTx(lease, () -> mcp.binding(fenced(lease, true)));
  }

  @Override
  public Server mcpServer(Lease lease, String server) {
    var value = mcpBinding(lease).servers().get(server);
    if (value == null) throw JdbcMcpRecords.unauthorized();
    return value;
  }

  @Override
  public int mcpDispatch(
      Lease lease,
      String invocation,
      String method,
      String requestId,
      String digest,
      boolean cleanup) {
    return leaseTx(
        lease,
        () -> {
          fenced(lease, !cleanup);
          var call =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
          if (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(s(call, "tool"))
              || !s(call, "state").equals("RUNNING"))
            throw new TaskFailure(409, "INVOCATION_STATE");
          String kind = cleanup ? "CLEANUP" : "NORMAL";
          int count =
              db.queryForObject(
                  "SELECT COUNT(*) FROM mcp_io WHERE tenant_id=? AND task_id=? AND kind=?",
                  Integer.class,
                  lease.tenant(),
                  lease.taskId(),
                  kind);
          if (count >= (cleanup ? 20 : 64)) throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
          if (cleanup
              && db.queryForObject(
                      "SELECT COUNT(*) FROM mcp_io WHERE tenant_id=? AND task_id=? AND invocation_id=? AND kind='CLEANUP'",
                      Integer.class,
                      lease.tenant(),
                      lease.taskId(),
                      invocation)
                  > 0) throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
          int ordinal =
              db.queryForObject(
                  "SELECT COALESCE(MAX(ordinal),0)+1 FROM mcp_io WHERE tenant_id=? AND task_id=? AND invocation_id=?",
                  Integer.class,
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
          db.update(
              "INSERT INTO mcp_io(tenant_id,task_id,invocation_id,ordinal,request_id,method,kind,phase,request_digest) VALUES(?,?,?,?,?,?,?,'DISPATCHED',?)",
              lease.tenant(),
              lease.taskId(),
              invocation,
              ordinal,
              requestId,
              method,
              kind,
              digest);
          return ordinal;
        });
  }

  @Override
  public void mcpResponded(Lease lease, String invocation, int ordinal, String error) {
    tx(
        () -> {
          fenced(lease, false);
          if (db.update(
                  "UPDATE mcp_io SET phase=?,error_code=? WHERE tenant_id=? AND task_id=? AND invocation_id=? AND ordinal=? AND phase='DISPATCHED'",
                  error == null ? "RESPONDED" : "FAILED",
                  error,
                  lease.tenant(),
                  lease.taskId(),
                  invocation,
                  ordinal)
              != 1) throw new TaskFailure(409, "INVOCATION_STATE");
          return null;
        });
  }

  @Override
  public void reserveMcpList(Lease lease, String invocation) {
    leaseTx(
        lease,
        () -> {
          var budget = bucket(lease.tenant());
          fenced(lease, true);
          var call =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
          if (!List.of("list_mcp_resources", "list_mcp_resource_templates")
                  .contains(s(call, "tool"))
              || !s(call, "state").equals("RUNNING"))
            throw new TaskFailure(409, "INVOCATION_STATE");
          if (db.queryForObject(
                      "SELECT COUNT(*) FROM reservation WHERE tenant_id=? AND task_id=? AND category='MCP_RESULT'",
                      Integer.class,
                      lease.tenant(),
                      lease.taskId())
                  >= 4
              || 1048576 > n(budget, "storage_limit") - storageHeld(budget))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          reserve(
              lease.tenant(),
              lease.taskId(),
              "mcp:" + invocation,
              invocation,
              "MCP_RESULT",
              1048576,
              "RESERVED",
              "KNOWN");
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held+1048576 WHERE tenant_id=?",
              lease.tenant());
          return null;
        });
  }

  @Override
  public String mcpPage(Lease lease, String query, String cursor) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var found =
              rows(
                  "SELECT c.*,r.body,r.body_digest,r.binding_digest,r.state FROM mcp_cursor c JOIN mcp_result r ON r.tenant_id=c.tenant_id AND r.task_id=c.task_id AND r.root_invocation_id=c.root_invocation_id WHERE c.tenant_id=? AND c.task_id=? AND c.token_digest=? AND c.expires_at>CURRENT_TIMESTAMP(6) AND r.expires_at>CURRENT_TIMESTAMP(6)",
                  lease.tenant(),
                  lease.taskId(),
                  Payloads.hash(cursor));
          if (found.size() != 1) throw new TaskFailure(409, "MCP_CURSOR_INVALID");
          var row = found.getFirst();
          String raw = s(row, "body");
          if (!"RETAINED".equals(s(row, "state"))
              || !query.equals(s(row, "query_digest"))
              || !s(task, "mcp_digest").equals(s(row, "binding_digest"))
              || raw == null
              || !Payloads.hash(raw).equals(s(row, "body_digest")))
            throw new TaskFailure(409, "MCP_CURSOR_INVALID");
          var snapshot = Payloads.read(raw, ListSnapshot.class);
          validateMcpList(task, snapshot);
          Integer page = snapshot.cursorPositions().get(Payloads.hash(cursor));
          if (page == null || page != n(row, "page_index") || !snapshot.queryDigest().equals(query))
            throw new TaskFailure(409, "MCP_CURSOR_INVALID");
          return snapshot.pages().get(page);
        });
  }

  private void validateMcpList(Map<String, Object> task, ListSnapshot snapshot) {
    try {
      new io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts().list(snapshot);
      if (!snapshot.tenant().equals(s(task, "tenant_id"))
          || !snapshot.owner().equals(s(task, "owner_actor_id"))
          || !snapshot.taskId().equals(s(task, "id"))
          || !snapshot.bindingDigest().equals(s(task, "mcp_digest")))
        throw JdbcMcpRecords.corrupt();
      String name =
          snapshot.method().equals("resources/list")
              ? "list_mcp_resources"
              : "list_mcp_resource_templates";
      var contracts = new io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts();
      var pin = tools(task).pins().get(name);
      var server = mcp.binding(task).servers().get(snapshot.server());
      if (server == null
          || pin == null
          || !snapshot
              .queryDigest()
              .equals(
                  Payloads.hash(
                      Payloads.json(
                          new Query(
                              snapshot.method(),
                              snapshot.server(),
                              server.digest(),
                              io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts.DIGEST,
                              pin.version(),
                              pin.digest(),
                              pin.installationEpoch()))))) throw JdbcMcpRecords.corrupt();
      var projected = new java.util.ArrayList<Object>();
      var positions = new java.util.LinkedHashMap<String, Integer>();
      int total = -1;
      for (int index = 0; index < snapshot.pages().size(); index++) {
        String page = snapshot.pages().get(index);
        if (page.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
          throw JdbcMcpRecords.corrupt();
        var node = Payloads.read(page, com.fasterxml.jackson.databind.JsonNode.class);
        contracts.contract(name).out().validate(node);
        if (!node.path("snapshotId").asText().equals(snapshot.rootInvocation())
            || !node.path("snapshotSha256").asText().equals(snapshot.projectionDigest())
            || !node.path("server").asText().equals(snapshot.server())
            || !node.path("serverSnapshotDigest").asText().equals(server.digest())
            || !node.path("schemaDigest").asText().equals(server.profile().schemaDigest())
            || !node.path("serverVersion").asText().equals(server.profile().serverInfo().version())
            || node.path("page").asInt() != index
            || total != -1 && total != node.path("total").asInt()) throw JdbcMcpRecords.corrupt();
        total = node.path("total").asInt();
        var values =
            node.path(name.equals("list_mcp_resources") ? "resources" : "resourceTemplates");
        if (node.path("returned").asInt() != values.size()) throw JdbcMcpRecords.corrupt();
        for (var value : values)
          projected.add(
              name.equals("list_mcp_resources")
                  ? Payloads.read(
                      value.toString(),
                      io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort.Resource.class)
                  : Payloads.read(
                      value.toString(),
                      io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort.Template.class));
        if (index + 1 < snapshot.pages().size()) {
          if (!node.path("nextCursor").isTextual()
              || positions.put(Payloads.hash(node.path("nextCursor").asText()), index + 1) != null)
            throw JdbcMcpRecords.corrupt();
        } else if (!node.path("nextCursor").isNull()) throw JdbcMcpRecords.corrupt();
      }
      if (projected.size() != total
          || !Payloads.hash(Payloads.json(projected)).equals(snapshot.projectionDigest())
          || !positions.equals(snapshot.cursorPositions())) throw JdbcMcpRecords.corrupt();
    } catch (java.io.IOException | IllegalArgumentException error) {
      throw JdbcMcpRecords.corrupt();
    }
  }

  @Override
  public void mcpSucceeded(Lease lease, String invocation, String output, ListSnapshot snapshot) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var producer =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
          if (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(
              s(producer, "tool"))) throw new TaskFailure(409, "INVOCATION_STATE");
          if ("SUCCEEDED".equals(s(producer, "state"))) {
            if (!output.equals(s(producer, "output_snapshot"))
                || !Payloads.hash(output).equals(s(producer, "output_digest")))
              throw JdbcMcpRecords.corrupt();
            return null;
          }
          if (!"RUNNING".equals(s(producer, "state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          if (snapshot != null) {
            validateMcpList(task, snapshot);
            if (!snapshot.rootInvocation().equals(invocation)
                || !snapshot.pages().getFirst().equals(output)) throw JdbcMcpRecords.corrupt();
            String raw = Payloads.json(snapshot);
            long size =
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + snapshot.cursorPositions().size() * 96L;
            if (size > 1048576) throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            var reservation =
                one(
                    "SELECT * FROM reservation WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE",
                    lease.tenant(),
                    lease.taskId(),
                    "mcp:" + invocation);
            if (!s(reservation, "state").equals("RESERVED"))
              throw new TaskFailure(409, "INVOCATION_STATE");
            db.update(
                "INSERT INTO mcp_result(tenant_id,task_id,root_invocation_id,server,method,binding_digest,projection_digest,body,body_digest,byte_size,expires_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6)),'RETAINED')",
                lease.tenant(),
                lease.taskId(),
                invocation,
                snapshot.server(),
                snapshot.method(),
                snapshot.bindingDigest(),
                snapshot.projectionDigest(),
                raw,
                Payloads.hash(raw),
                size);
            for (var cursor : snapshot.cursorPositions().entrySet())
              db.update(
                  "INSERT INTO mcp_cursor(tenant_id,task_id,token_digest,root_invocation_id,page_index,query_digest,expires_at) SELECT tenant_id,task_id,?,root_invocation_id,?,?,expires_at FROM mcp_result WHERE tenant_id=? AND task_id=? AND root_invocation_id=?",
                  cursor.getKey(),
                  cursor.getValue(),
                  snapshot.queryDigest(),
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
            db.update(
                "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                n(reservation, "amount") - size,
                lease.tenant());
            db.update(
                "UPDATE reservation SET actual=?,state='SETTLED' WHERE tenant_id=? AND id=?",
                size,
                lease.tenant(),
                "mcp:" + invocation);
          }
          commitTool(lease, invocation, output, true);
          return null;
        });
  }

  private void cleanupMcp() {
    for (var candidate :
        rows(
            "SELECT DISTINCT r.tenant_id,r.task_id FROM reservation r JOIN task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id LEFT JOIN mcp_result m ON m.tenant_id=r.tenant_id AND m.task_id=r.task_id AND r.id=CONCAT('mcp:',m.root_invocation_id) WHERE r.category='MCP_RESULT' AND r.state IN ('RESERVED','SETTLED') AND t.state IN ('SUCCEEDED','FAILED','CANCELLED') AND (m.expires_at<CURRENT_TIMESTAMP(6) OR m.root_invocation_id IS NULL AND t.updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY) LIMIT 32")) {
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id"), taskId = s(candidate, "task_id");
            bucket(tenant);
            var task = locked(tenant, taskId);
            if (!writersQuiescent(task)
                || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state"))
                || !rows(
                        "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND state='HELD_UNKNOWN'",
                        tenant,
                        taskId)
                    .isEmpty()) return null;
            for (var reservation :
                rows(
                    "SELECT r.* FROM reservation r LEFT JOIN mcp_result m ON m.tenant_id=r.tenant_id AND m.task_id=r.task_id AND r.id=CONCAT('mcp:',m.root_invocation_id) WHERE r.tenant_id=? AND r.task_id=? AND r.category='MCP_RESULT' AND r.state IN ('RESERVED','SETTLED') AND (m.expires_at<CURRENT_TIMESTAMP(6) OR m.root_invocation_id IS NULL AND ?<CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY) FOR UPDATE",
                    tenant,
                    taskId,
                    task.get("updated_at"))) {
              String root = s(reservation, "id").substring(4);
              db.update(
                  "DELETE FROM mcp_cursor WHERE tenant_id=? AND task_id=? AND root_invocation_id=?",
                  tenant,
                  taskId,
                  root);
              db.update(
                  "UPDATE mcp_result SET body=NULL,state='DELETED' WHERE tenant_id=? AND task_id=? AND root_invocation_id=?",
                  tenant,
                  taskId,
                  root);
              long held =
                  reservation.get("actual") == null
                      ? n(reservation, "amount")
                      : n(reservation, "actual");
              db.update(
                  "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                  held,
                  tenant);
              db.update(
                  "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                  tenant,
                  reservation.get("id"));
              event(task, "mcp-cleanup:" + root, "mcp.result_deleted", null);
            }
            return null;
          });
    }
  }

  private boolean imagesAvailable() {
    if (imageStore == null) return false;
    try {
      imageStore.check();
      io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageNormalizer.available();
      return true;
    } catch (io.github.lovexiaopeng.jimu.plugin.api.FileFailure unavailable) {
      return false;
    }
  }

  public void nativeSearchAvailability(java.util.function.BooleanSupplier availability) {
    this.searchAvailable = availability;
  }

  public JdbcLedger(DataSource source, ModelProfiles models) {
    source = SessionQueryDeadline.source(source);
    this.models = models;
    db =
        new JdbcTemplate(source) {
          @Override
          protected void applyStatementSettings(java.sql.Statement statement)
              throws java.sql.SQLException {
            super.applyStatementSettings(statement);
            if (sessionQueryDeadline.get() != null) {
              int remaining = sessionRemainingMillis();
              statement.getConnection().setNetworkTimeout(Runnable::run, remaining);
              try (var settings = statement.getConnection().createStatement()) {
                settings.execute("SET SESSION max_execution_time=" + remaining);
              }
              statement.setQueryTimeout(Math.max(1, (remaining + 999) / 1000));
            }
          }
        };
    sessions = new JdbcSessionRecords(db);
    images = new JdbcImageRecords(db);
    mcp = new JdbcMcpRecords(db);
    db.setQueryTimeout(5);
    executionScopes = new JdbcExecutionScopes(db, models);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
  }

  private <T> T tx(Supplier<T> work) {
    AdmissionResult<T> result =
        transactions.execute(
            status -> {
              try {
                return new AdmissionResult<T>(work.get(), null);
              } catch (TaskFailure failure) {
                // Scope checks run before admission writes. Only the deliberate persistent
                // integrity rejection (or already admitted settlement) commits here.
                if (!List.of(
                        "EVALUATION_SCOPE_MISMATCH",
                        "MODEL_SETTLEMENT_PROOF_INVALID",
                        "BUSINESS_POLICY_CONFLICT")
                    .contains(failure.code())) throw failure;
                return new AdmissionResult<T>(null, failure);
              }
            });
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Snapshot mediaSnapshot(
      Lease lease) {
    return leaseTx(lease, () -> images.snapshot(fenced(lease, true)));
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.Raw imageInput(
      Lease lease, String path) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages.require(snapshot(task));
          return images.raw(task, path);
        });
  }

  @Override
  public String reserveImage(Lease lease, String invocation, String inputId) {
    return leaseTx(
        lease,
        () -> {
          var budget = bucket(lease.tenant());
          var task = fenced(lease, true);
          if (1048576 > n(budget, "storage_limit") - storageHeld(budget))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String image = id();
          images.reserve(task, invocation, image, inputId);
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held+1048576 WHERE tenant_id=?",
              lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              image,
              null,
              "MEDIA_STORAGE",
              1048576,
              "RESERVED",
              "KNOWN");
          return image;
        });
  }

  @Override
  public void imageSucceeded(
      Lease lease,
      String invocation,
      String key,
      io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result result) {
    leaseTx(
        lease,
        () -> {
          bucket(lease.tenant());
          var task = fenced(lease, true);
          images.succeeded(task, invocation, key, result);
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=? WHERE tenant_id=? AND id=? AND category='MEDIA_STORAGE' AND state='RESERVED'",
              result.image().size(),
              lease.tenant(),
              result.image().imageId());
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
              1048576 - result.image().size(),
              lease.tenant());
          toolSucceeded(lease, invocation, Payloads.json(result));
          return null;
        });
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ObjectValue imageObject(
      Lease lease, io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result ref) {
    return leaseTx(lease, () -> images.object(fenced(lease, true), ref));
  }

  @Override
  public List<io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ObjectValue> stepImages(
      Lease lease, String step) {
    return leaseTx(lease, () -> images.step(fenced(lease, true), step));
  }

  @Override
  public void imageDecodeTimedOut(Lease lease) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, false);
          db.update(
              "UPDATE task SET state='CANCEL_REQUESTED',cancel_requested_at=COALESCE(cancel_requested_at,CURRENT_TIMESTAMP(6)),code=COALESCE(code,'IMAGE_DECODE_TIMEOUT') WHERE tenant_id=? AND id=?",
              lease.tenant(),
              lease.taskId());
          event(task, "image-decode-timeout", "task.cancel_requested", "IMAGE_DECODE_TIMEOUT");
          return null;
        });
  }

  private String requireScope(
      String tenant,
      Map<String, Object> evaluation,
      Map<String, Object> task,
      ModelSnapshot model) {
    try {
      if (task == null && models.businessPolicy() != null || task != null && businessTask(task)) {
        requireBusiness(tenant, evaluation, task, model);
        return null;
      }
      if (!executionScopes.needed(tenant, task)) return null;
      if (task != null) {
        if (task.get("execution_scope_digest") == null)
          throw new TaskFailure(409, "EVALUATION_SCOPE_LEGACY_UNBOUND");
        try {
          model = historicalSnapshot(task);
        } catch (TaskFailure | IllegalArgumentException corrupt) {
          throw new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH");
        }
      }
      String digest = executionScopes.require(tenant, evaluation, task, model);
      if (task != null) {
        try {
          models.available(model);
          checkEvaluation(task, evaluation, model);
        } catch (TaskFailure | IllegalArgumentException corrupt) {
          throw new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH");
        }
      }
      return digest;
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw new TaskFailure(503, "EVALUATION_SCOPE_UNAVAILABLE");
    } catch (TaskFailure failure) {
      if (List.of("EVALUATION_SCOPE_MISMATCH", "BUSINESS_POLICY_CONFLICT").contains(failure.code())
          && evaluation != null) {
        db.update(
            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
            tenant,
            s(evaluation, "id"));
        if (task != null)
          event(
              task,
              "scope-rejected:" + n(task, "fence"),
              "evaluation.scope_rejected",
              failure.code());
      }
      throw failure;
    }
  }

  private boolean businessTask(Map<String, Object> task) {
    return n(task, "record_version") == 2
        || task.get("business_policy_snapshot") != null
        || task.get("business_policy_digest") != null;
  }

  private void requireBusiness(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    BusinessPolicies.Policy policy;
    try {
      if (task == null) policy = models.businessPolicy();
      else {
        String json = s(task, "business_policy_snapshot");
        if (!(n(task, "record_version") == 2 || n(task, "record_version") == 3)
            || json == null
            || !Payloads.hash(json).equals(task.get("business_policy_digest"))
            || task.get("execution_scope_digest") != null
            || task.get("evaluation_snapshot") != null
            || !java.util.Objects.equals(
                task.get("evaluation_run_id"), run == null ? null : run.get("id")))
          throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
        policy = Payloads.read(json, BusinessPolicies.Policy.class);
        if (!(n(task, "record_version") == 2 && policy.version() == 1
            || n(task, "record_version") == 3 && policy.version() == 2))
          throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
        if (!BusinessPolicies.canonical(policy).equals(json))
          throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
        model = historicalSnapshot(task);
      }
      BusinessPolicies.validate(policy);
      models.retainedBusiness(policy);
      if (!tenant.equals(policy.anchor().tenantId()))
        throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
      executionScopes.businessAnchor(policy, run);
      if (plan(run) == null) throw new TaskFailure(503, "BUSINESS_POLICY_UNAVAILABLE");
      BusinessPolicies.profile(policy, model, models.localEvaluation(model));
      models.available(model);
    } catch (IllegalArgumentException corrupt) {
      throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
    } catch (TaskFailure invalid) {
      if (invalid.status() == 409) throw new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
      throw invalid;
    }
  }

  private record AdmissionResult<T>(T value, TaskFailure rejection) {}

  // Material checks precede quota/IO admission. Commit STOPPED with the rejection fact,
  // then throw outside the transaction; a process exit before task failure cannot undo it.
  private <T> T leaseTx(Lease lease, Supplier<T> work) {
    AdmissionResult<T> result =
        tx(
            () -> {
              try {
                return new AdmissionResult<T>(directoryTransaction(lease, work), null);
              } catch (TaskFailure rejection) {
                if (!rejection.code().equals("EVALUATION_MATERIAL_MISMATCH")) throw rejection;
                db.update(
                    "UPDATE evaluation_budget b JOIN task t ON t.tenant_id=b.tenant_id AND t.evaluation_run_id=b.id SET b.state='STOPPED' WHERE t.tenant_id=? AND t.id=?",
                    lease.tenant(),
                    lease.taskId());
                return new AdmissionResult<T>(null, rejection);
              }
            });
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  private <T> T directoryTransaction(Lease lease, Supplier<T> work) {
    if (!lease.capabilities().contains("working_directory")) return work.get();
    return db.execute(
        (org.springframework.jdbc.core.ConnectionCallback<T>)
            connection -> {
              long remaining =
                  java.time.Duration.between(Instant.now(), lease.deadline()).toSeconds();
              if (remaining < 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
              int prior;
              try (var statement = connection.createStatement();
                  var result =
                      statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                result.next();
                prior = result.getInt(1);
              }
              try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout=" + Math.min(2, remaining));
              }
              try {
                return work.get();
              } finally {
                try (var statement = connection.createStatement()) {
                  statement.execute("SET SESSION innodb_lock_wait_timeout=" + prior);
                }
              }
            });
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  private Map<String, Object> one(String sql, Object... args) {
    var result = rows(sql, args);
    if (result.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return result.getFirst();
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static Instant time(Map<String, Object> row, String key) {
    return row.get(key) == null
        ? null
        : ((java.time.LocalDateTime) row.get(key)).toInstant(java.time.ZoneOffset.UTC);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private Map<String, Object> bucket(String tenant) {
    return one("SELECT * FROM tenant_budget WHERE tenant_id=? FOR UPDATE", tenant);
  }

  private void identity(Identity identity) {
    var result =
        rows(
            "SELECT enabled FROM actor_access WHERE tenant_id=? AND actor_id=? FOR SHARE",
            identity.tenant(),
            identity.actor());
    if (result.isEmpty() || !Boolean.TRUE.equals(result.getFirst().get("enabled")))
      throw new TaskFailure(403, "ACCESS_REVOKED");
  }

  private long epoch(Identity identity, List<String> capabilities) {
    identity(identity);
    long epoch = 0;
    for (String capability : capabilities) {
      var installed =
          rows(
              "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=? FOR SHARE",
              identity.tenant(),
              capability);
      if (installed.isEmpty() || !Boolean.TRUE.equals(installed.getFirst().get("enabled")))
        throw new TaskFailure(403, "CAPABILITY_REVOKED");
      // Each enabled capability has its own version; sum is monotonic because epochs never
      // decrease.
      epoch += n(installed.getFirst(), "epoch");
    }
    return epoch;
  }

  @Override
  public List<String> capabilities(Identity identity) {
    return tx(
        () -> {
          identity(identity);
          return db
              .queryForList(
                  "SELECT plugin_id FROM installation WHERE tenant_id=? AND enabled=TRUE ORDER BY plugin_id",
                  String.class,
                  identity.tenant())
              .stream()
              .filter(
                  name ->
                      mcp.enabled()
                          || !io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(
                              name))
              .filter(name -> questionsEnabled || !name.equals("ask_user_question"))
              .filter(name -> !name.equals("working_directory") || directoryAvailable(identity))
              .filter(name -> !name.equals("todo_write") || todoAvailable(identity))
              .filter(name -> !name.equals("session_event_read") || sessionAvailable(identity))
              .filter(
                  name -> searchAvailable.getAsBoolean() || !List.of("glob", "grep").contains(name))
              .filter(
                  name ->
                      !name.equals("read_image")
                          || imagesAvailable()
                              && io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages
                                  .enabled(models.selected(identity.tenant())))
              .toList();
        });
  }

  @Override
  public View submit(Identity identity, String key, Request request) {
    return submit(identity, key, request, List.of());
  }

  @Override
  public View submit(
      Identity identity,
      String key,
      Request original,
      List<io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Input> input) {
    var descriptors =
        input.isEmpty()
            ? null
            : io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageInputs.describe(input);
    if (original.images() != null && !original.images().equals(descriptors))
      throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    var request =
        new Request(
            original.goal(),
            original.capabilityIds(),
            original.resourceIds(),
            original.budget(),
            descriptors);
    boolean sessionRead = request.capabilityIds().contains("session_event_read");
    if (sessionRead
        && (!request.capabilityIds().equals(List.of("session_event_read"))
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean directory = request.capabilityIds().contains("working_directory");
    if (directory
        && (!new java.util.HashSet<>(request.capabilityIds())
                .equals(java.util.Set.of("working_directory", "read", "write", "edit"))
            || request.capabilityIds().size() != 4
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean todo = request.capabilityIds().contains("todo_write");
    if (todo
        && (!request.capabilityIds().equals(List.of("todo_write"))
            || !request.resourceIds().isEmpty()
            || original.images() != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    boolean ask = request.capabilityIds().contains("ask_user_question");
    if (ask
        && (!request.capabilityIds().equals(List.of("ask_user_question"))
            || !request.resourceIds().isEmpty()
            || descriptors != null)) throw new TaskFailure(400, "INVALID_CAPABILITIES");
    if (ask && !questionsEnabled) throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
    boolean media = request.capabilityIds().contains("read_image");
    boolean mcpTask =
        request.capabilityIds().stream()
            .anyMatch(io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES::contains);
    if (mcpTask
        && (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.containsAll(
                request.capabilityIds())
            || !request.resourceIds().isEmpty()))
      throw new TaskFailure(400, "INVALID_CAPABILITIES");
    if (mcpTask && !mcp.enabled()) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    if (descriptors != null && !media) throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    return tx(
        () -> {
          // InnoDB also locks the evaluation parent while checking task's FK. Acquire that
          // parent before tenant_budget, including acceptance and idempotent retries.
          var binding =
              rows(
                  "SELECT evaluation_run_id FROM task WHERE tenant_id=? AND owner_actor_id=? AND idempotency_key=?",
                  identity.tenant(),
                  identity.actor(),
                  key);
          String run;
          if (binding.isEmpty()) {
            run = executionScopes.targetRun(identity.tenant(), null);
            if (run == null) {
              var candidate = models.selected(identity.tenant());
              run = candidate.paid() ? models.evaluationRunId() : null;
            }
          } else run = s(binding.getFirst(), "evaluation_run_id");
          var evaluation = evaluation(identity.tenant(), run);
          var budget = bucket(identity.tenant());
          identity(identity);
          String snapshot = Payloads.json(request), digest = Payloads.hash(snapshot);
          var old =
              rows(
                  "SELECT * FROM task WHERE tenant_id=? AND owner_actor_id=? AND idempotency_key=? FOR UPDATE",
                  identity.tenant(),
                  identity.actor(),
                  key);
          if (!old.isEmpty()) {
            if (JdbcSessionRecords.task(old.getFirst())) {
              access(old.getFirst());
              sessionAdmission(old.getFirst());
            }
            if (directoryTask(old.getFirst())) {
              access(old.getFirst());
              if (!"PRESENT".equals(s(directoryProjection(old.getFirst()), "content_state")))
                throw new TaskFailure(410, "DIRECTORY_CONTENT_EXPIRED");
            }
            if (todoTask(old.getFirst())) {
              access(old.getFirst());
              if ("PURGED".equals(s(todoProjection(old.getFirst()), "content_state")))
                throw new TaskFailure(410, "TODO_CONTENT_EXPIRED");
            }
            if (ask) access(old.getFirst());
            if (!Payloads.hash(s(old.getFirst(), "request_snapshot"))
                .equals(s(old.getFirst(), "request_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            if (!Payloads.read(s(old.getFirst(), "request_snapshot"), Request.class)
                .equals(request)) throw new TaskFailure(409, "IDEMPOTENCY_CONFLICT");
            if (mcpTask || ask) access(old.getFirst());
            return view(old.getFirst());
          }
          if (media) {
            io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages.require(
                models.selected(identity.tenant()));
            if (!imagesAvailable()
                || db.queryForObject("SELECT @@max_allowed_packet", Long.class) < 16777216)
              throw new TaskFailure(503, "IMAGE_STORE_UNAVAILABLE");
            if (request.capabilityIds().contains("write")
                || request.capabilityIds().contains("present")
                    && !request.capabilityIds().contains("compose_report"))
              throw new TaskFailure(400, "INVALID_CAPABILITIES");
          }
          long installationEpoch = epoch(identity, request.capabilityIds());
          var mcpBinding = mcpTask ? mcp.bind(identity.tenant(), identity.actor()) : null;
          if (request.capabilityIds().stream()
                  .anyMatch(name -> List.of("glob", "grep").contains(name))
              && !searchAvailable.getAsBoolean())
            throw new TaskFailure(503, "SEARCH_NOT_CONFIGURED");
          var pins = new java.util.LinkedHashMap<String, PluginPin>();
          for (String capability : request.capabilityIds()) {
            var installed =
                one(
                    "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                    identity.tenant(),
                    capability);
            pins.put(
                capability,
                new PluginPin(
                    s(installed, "plugin_version"),
                    s(installed, "plugin_digest"),
                    n(installed, "epoch")));
          }
          String toolSnapshot = Payloads.json(new ToolSnapshot(1, pins));
          if (pins.containsKey("compose_report")
              && media != pins.get("compose_report").version().equals("0.2.0"))
            throw new TaskFailure(503, "PLUGIN_PIN_MISMATCH");
          var model = models.selected(identity.tenant());
          String scopeDigest = requireScope(identity.tenant(), evaluation, null, model);
          var businessPolicy = models.businessPolicy();
          if (ask
              && (model.paid()
                  || businessPolicy != null
                  || evaluation != null
                  || !(models.fixture() || models.localEvaluation(model))))
            throw new TaskFailure(403, "QUESTION_NOT_AUTHORIZED");
          if (sessionRead) {
            if (!sessionReadEnabled) throw new TaskFailure(403, "SESSION_READ_DISABLED");
            models.requireSessionRead(model);
            if (evaluation != null || businessPolicy != null || models.scopeRequirement() != null)
              throw new TaskFailure(403, "SESSION_READ_DISABLED");
            var pin = pins.get("session_event_read");
            if (!pin.version().equals(sessions.contracts.descriptor().version())
                || !pin.digest().equals(sessions.contracts.descriptor().outputSha256()))
              throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            if (n(budget, "history_storage_limit") - n(budget, "history_storage_held") < 8388608)
              throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > 180)
              throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          }
          if (directory) {
            if (!directoryEnabled) throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
            models.requireDirectory(model);
            if (evaluation != null || businessPolicy != null || models.scopeRequirement() != null)
              throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
            for (String name : request.capabilityIds()) {
              var descriptor =
                  name.equals("working_directory")
                      ? directories.descriptor()
                      : directoryFileContract(name).descriptor();
              if (!pins.get(name).version().equals(descriptor.version())
                  || !pins.get(name).digest().equals(descriptor.outputSha256()))
                throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            }
            if (n(budget, "directory_storage_limit") - n(budget, "directory_storage_held")
                < 8388608) throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20
                || request.budget().deadlineSeconds() > 180)
              throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
          }
          if (todo) {
            if (!todosEnabled) throw new TaskFailure(403, "TODO_NOT_AUTHORIZED");
            models.requireTodo(model);
            if (evaluation != null || businessPolicy != null || models.scopeRequirement() != null)
              throw new TaskFailure(403, "TODO_NOT_AUTHORIZED");
            if (!pins.get("todo_write").version().equals(todos.descriptor().version())
                || !pins.get("todo_write").digest().equals(todos.descriptor().outputSha256()))
              throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            if (n(budget, "todo_storage_limit") - n(budget, "todo_storage_held") < 2097152)
              throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
            if (request.budget().maxModelRequests() > 8
                || request.budget().maxToolInvocations() > 20)
              throw new TaskFailure(429, "TODO_EXECUTION_LIMIT");
          }
          String questionBinding =
              ask ? questions.binding(pins.get("ask_user_question").version()) : null;
          if (ask
              && !pins.get("ask_user_question")
                  .digest()
                  .equals(
                      questions
                          .contract(pins.get("ask_user_question").version())
                          .descriptor()
                          .outputSha256())) throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          if (ask && n(budget, "question_tasks_held") >= n(budget, "question_tasks_limit"))
            throw new TaskFailure(429, "QUESTION_TASK_LIMIT");
          if (ask && 2097152 > n(budget, "storage_limit") - storageHeld(budget))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          if (mcpTask
              && (model.paid()
                  || businessPolicy != null
                  || !(models.fixture() || models.localEvaluation(model))))
            throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
          if (request.capabilityIds().contains("compose_report")
              && request.capabilityIds().contains("write"))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (businessPolicy != null
              && businessPolicy.version() == 1
              && request.capabilityIds().contains("present")
              && !request.capabilityIds().stream()
                  .filter(name -> !List.of("edit", "glob", "grep").contains(name))
                  .toList()
                  .equals(List.of("web_fetch", "read", "compose_report", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (businessPolicy != null
              && businessPolicy.version() == 2
              && (!media
                  || !request
                      .capabilityIds()
                      .containsAll(List.of("read", "read_image", "compose_report", "present"))
                  || request.capabilityIds().stream()
                      .anyMatch(c -> List.of("write", "web_search").contains(c))))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          Long explicitCost = request.budget().maxCostMicros();
          if (explicitCost != null
              && (explicitCost < 1 || businessPolicy == null && explicitCost > 1000000))
            throw new TaskFailure(400, "INVALID_BUDGET");
          if (request.budget().maxCostMicros() != null && !model.paid())
            throw new TaskFailure(400, "CURRENCY_BUDGET_NOT_CONFIGURED");
          models.admit(model);
          long taskCost = explicitCost == null ? 1000000 : explicitCost;
          long taskTokens = 65536;
          if (businessPolicy != null) {
            long singleCost;
            long hostCost;
            try {
              long singleTokens =
                  Math.addExact(model.price().inputTokenUpper(), model.maxOutputTokens());
              singleCost =
                  ModelCosts.amount(
                      model.price(), model.price().inputTokenUpper(), model.maxOutputTokens());
              taskTokens = Math.multiplyExact(singleTokens, request.budget().maxModelRequests());
              hostCost =
                  Math.min(
                      Math.multiplyExact(singleCost, request.budget().maxModelRequests()),
                      n(evaluation, "max_cost_micros"));
            } catch (ArithmeticException unrepresentable) {
              throw new TaskFailure(503, "MODEL_COST_BOUND_UNAVAILABLE");
            }
            if (explicitCost != null && explicitCost > hostCost)
              throw new TaskFailure(400, "INVALID_BUDGET");
            taskCost = explicitCost == null ? hostCost : explicitCost;
            if (taskCost < singleCost) throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          }
          var evaluationBinding = businessPolicy == null ? bindEvaluation(evaluation, model) : null;
          var material =
              evaluationBinding == null
                  ? null
                  : EvaluationMaterials.load(
                      models.evaluationSelection().plan(), evaluationBinding.sample());
          if (material != null) EvaluationMaterials.require(request.goal().equals(material.goal()));
          if (evaluationBinding != null
              && !request.capabilityIds().equals(List.of("web_fetch", "read", "write", "present")))
            throw new TaskFailure(400, "INVALID_CAPABILITIES");
          if (evaluationBinding != null
              && !rows(
                      "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_model=? AND evaluation_sample=? LIMIT 1",
                      identity.tenant(),
                      run,
                      model.model(),
                      evaluationBinding.sample())
                  .isEmpty()) throw new TaskFailure(409, "EVALUATION_SAMPLE_ALREADY_BOUND");
          String modelSnapshot = Payloads.json(model);
          if (n(budget, "queued") >= n(budget, "queue_limit"))
            throw new TaskFailure(429, "QUEUE_LIMIT");
          String task = id();
          db.update(
              "INSERT INTO task(tenant_id,id,owner_actor_id,idempotency_key,request_digest,request_snapshot,goal,capabilities,installation_epoch,state,deadline,max_models,max_tools) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),?,?)",
              identity.tenant(),
              task,
              identity.actor(),
              key,
              digest,
              snapshot,
              request.goal(),
              String.join(",", request.capabilityIds()),
              installationEpoch,
              request.budget().deadlineSeconds(),
              request.budget().maxModelRequests(),
              request.budget().maxToolInvocations());
          db.update(
              "UPDATE task SET model_snapshot=?,model_digest=?,evaluation_run_id=?,max_cost_micros=?,tools_snapshot=?,tools_digest=?,execution_scope_digest=?,report_time=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              modelSnapshot,
              Payloads.hash(modelSnapshot),
              model.paid() ? run : null,
              taskCost,
              toolSnapshot,
              Payloads.hash(toolSnapshot),
              scopeDigest,
              identity.tenant(),
              task);
          if (sessionRead) {
            var accepted = locked(identity.tenant(), task);
            sessions.create(accepted);
            db.update(
                "UPDATE tenant_budget SET history_storage_held=history_storage_held+8388608 WHERE tenant_id=?",
                identity.tenant());
            reserve(
                identity.tenant(),
                task,
                task + ":session-history",
                null,
                "SESSION_HISTORY_STORAGE",
                8388608,
                "RESERVED",
                "KNOWN");
          }
          if (directory) {
            String namespace = Payloads.hash(identity.tenant() + ":" + task),
                directoryBinding = directories.binding(namespace);
            db.update(
                "INSERT INTO task_directory(tenant_id,task_id,binding_snapshot,binding_digest,namespace_digest,cwd,retain_until) SELECT tenant_id,id,?,?,?,'/',TIMESTAMPADD(SECOND,604800,created_at) FROM task WHERE tenant_id=? AND id=?",
                directoryBinding,
                Payloads.hash(directoryBinding),
                namespace,
                identity.tenant(),
                task);
            db.update(
                "UPDATE tenant_budget SET directory_storage_held=directory_storage_held+8388608 WHERE tenant_id=?",
                identity.tenant());
            reserve(
                identity.tenant(),
                task,
                task + ":directory-storage",
                null,
                "DIRECTORY_STORAGE",
                8388608,
                "RESERVED",
                "KNOWN");
            directoryStorage(locked(identity.tenant(), task), 0);
          }
          if (todo) {
            db.update(
                "INSERT INTO task_todo_projection(tenant_id,task_id,binding_snapshot,binding_digest,retain_until) SELECT tenant_id,id,?,?,TIMESTAMPADD(SECOND,604800,created_at) FROM task WHERE tenant_id=? AND id=?",
                todos.binding(),
                Payloads.hash(todos.binding()),
                identity.tenant(),
                task);
            db.update(
                "UPDATE tenant_budget SET todo_storage_held=todo_storage_held+2097152 WHERE tenant_id=?",
                identity.tenant());
            reserve(
                identity.tenant(),
                task,
                task + ":todo-storage",
                null,
                "TODO_STORAGE",
                2097152,
                "RESERVED",
                "KNOWN");
            todoStorage(locked(identity.tenant(), task), 0);
          }
          if (ask) {
            db.update(
                "UPDATE task SET question_binding=?,question_binding_digest=?,question_cleanup_state='RETAINED' WHERE tenant_id=? AND id=?",
                questionBinding,
                Payloads.hash(questionBinding),
                identity.tenant(),
                task);
            db.update(
                "UPDATE tenant_budget SET question_tasks_held=question_tasks_held+1,storage_held=storage_held+2097152 WHERE tenant_id=?",
                identity.tenant());
            reserve(
                identity.tenant(),
                task,
                task + ":question-task",
                null,
                "QUESTION_TASK",
                1,
                "RESERVED",
                "KNOWN");
            reserve(
                identity.tenant(),
                task,
                task + ":question-storage",
                null,
                "QUESTION_STORAGE",
                2097152,
                "RESERVED",
                "KNOWN");
          }
          if (mcpBinding != null) {
            String mcpSnapshot = Payloads.json(mcpBinding);
            db.update(
                "UPDATE task SET mcp_snapshot=?,mcp_digest=? WHERE tenant_id=? AND id=?",
                mcpSnapshot,
                Payloads.hash(mcpSnapshot),
                identity.tenant(),
                task);
          }
          if (businessPolicy != null) {
            String policyJson = BusinessPolicies.canonical(businessPolicy);
            db.update(
                "UPDATE task SET record_version=2,business_policy_snapshot=?,business_policy_digest=?,max_tokens=? WHERE tenant_id=? AND id=?",
                policyJson,
                Payloads.hash(policyJson),
                taskTokens,
                identity.tenant(),
                task);
          }
          if (media) {
            long held = input.stream().mapToLong(i -> i.dataBase64().length()).sum();
            if (held > n(budget, "storage_limit") - storageHeld(budget))
              throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            images.insert(identity, task, input, descriptors == null ? List.of() : descriptors);
            if (held > 0) {
              db.update(
                  "UPDATE tenant_budget SET storage_held=storage_held+? WHERE tenant_id=?",
                  held,
                  identity.tenant());
              reserve(
                  identity.tenant(),
                  task,
                  task + ":media-input",
                  null,
                  "MEDIA_INPUT",
                  held,
                  "SETTLED",
                  "KNOWN");
              db.update(
                  "UPDATE reservation SET actual=amount WHERE tenant_id=? AND id=?",
                  identity.tenant(),
                  task + ":media-input");
            }
          }
          if (evaluationBinding != null) {
            var selection = models.evaluationSelection();
            var plan = selection.plan();
            String bindingSnapshot = Payloads.json(evaluationBinding);
            db.update(
                "UPDATE task SET evaluation_snapshot=?,evaluation_digest=?,evaluation_phase=?,evaluation_sample=?,evaluation_model=?,max_models=LEAST(max_models,?),max_tokens=?,max_cost_micros=LEAST(max_cost_micros,?) WHERE tenant_id=? AND id=?",
                bindingSnapshot,
                Payloads.hash(bindingSnapshot),
                selection.phase(),
                selection.sample(),
                model.model(),
                selection.sample().equals("W1") ? 7 : 6,
                (selection.sample().equals("W1") ? 7L : 6L) * 1004096,
                request.budget().maxCostMicros() == null
                    ? EvaluationPlans.taskCost(plan, model.model(), selection.sample())
                    : Math.min(
                        request.budget().maxCostMicros(),
                        EvaluationPlans.taskCost(plan, model.model(), selection.sample())),
                identity.tenant(),
                task);
            String materialSnapshot = Payloads.json(material);
            var accepted = locked(identity.tenant(), task);
            String system =
                material
                    .systemTemplate()
                    .replace("{{HOST_REPORT_TIME}}", time(accepted, "report_time").toString());
            db.update(
                "UPDATE task SET material_snapshot=?,material_digest=?,material_system_digest=? WHERE tenant_id=? AND id=?",
                materialSnapshot,
                Payloads.hash(materialSnapshot),
                Payloads.hash(system),
                identity.tenant(),
                task);
            // The HTTP budget only tightens this host-selected private task ceiling.
            if (request.budget().maxCostMicros() == null)
              db.update(
                  "UPDATE task SET max_cost_micros=? WHERE tenant_id=? AND id=?",
                  EvaluationPlans.taskCost(plan, model.model(), selection.sample()),
                  identity.tenant(),
                  task);
          }
          db.update(
              "UPDATE tenant_budget SET queued=queued+1 WHERE tenant_id=?", identity.tenant());
          reserve(identity.tenant(), task, task + ":queue", null, "QUEUE", 1, "RESERVED", "KNOWN");
          var row = locked(identity.tenant(), task);
          event(row, "accepted", "task.accepted", null);
          return view(locked(identity.tenant(), task));
        });
  }

  private Map<String, Object> locked(String tenant, String task) {
    return one(
        "SELECT *,lease_until>CURRENT_TIMESTAMP(6) AS live,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND id=? FOR UPDATE",
        tenant,
        task);
  }

  private Map<String, Object> visible(Identity identity, String task) {
    identity(identity);
    return one(
        "SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=?",
        identity.tenant(),
        task,
        identity.actor());
  }

  private List<String> caps(Map<String, Object> row) {
    return Arrays.asList(s(row, "capabilities").split(","));
  }

  private void access(Map<String, Object> row) {
    long currentEpoch =
        epoch(new Identity(s(row, "tenant_id"), s(row, "owner_actor_id")), caps(row));
    if (JdbcSessionRecords.task(row)) sessionAccess(row);
    if (directoryTask(row)) directoryAccess(row);
    if (todoTask(row)) todoAccess(row);
    if (questionTask(row)) questionAccess(row);
    else if (currentEpoch != n(row, "installation_epoch"))
      throw new TaskFailure(403, "CAPABILITY_REVOKED");
    if (caps(row).stream()
        .anyMatch(io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES::contains)) {
      if (!io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.containsAll(caps(row)))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      mcp.binding(row);
    } else if (row.get("mcp_snapshot") != null || row.get("mcp_digest") != null)
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  @Override
  public View get(Identity identity, String task) {
    return tx(
        () -> {
          var row = visible(identity, task);
          access(row);
          return view(row);
        });
  }

  private View view(Map<String, Object> row) {
    var artifacts =
        rows(
                "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND state='PUBLISHED' ORDER BY id",
                s(row, "tenant_id"),
                s(row, "id"))
            .stream()
            .map(this::artifact)
            .toList();
    String summary = s(row, "summary");
    if (JdbcSessionRecords.task(row)) {
      var h = sessions.history(row, false);
      if (n(h, "retained") != 1 || !"PRESENT".equals(s(h, "content_state"))) summary = null;
    }
    return new View(
        s(row, "id"),
        s(row, "state"),
        n(row, "revision"),
        time(row, "created_at"),
        time(row, "updated_at"),
        time(row, "cancel_requested_at"),
        s(row, "code"),
        summary,
        artifacts);
  }

  private Artifact artifact(Map<String, Object> row) {
    return new Artifact(
        s(row, "id"),
        s(row, "media_type"),
        n(row, "size"),
        s(row, "sha256"),
        time(row, "expires_at"));
  }

  @Override
  public View cancel(Identity identity, String task) {
    return tx(
        () -> {
          bucket(identity.tenant());
          visible(identity, task);
          var row = locked(identity.tenant(), task);
          if (row.get("cancel_requested_at") != null) return view(row);
          if (view(row).terminal()) {
            // Unknown outcome is terminal for business execution, but does not prove the
            // writer exited. Persist a stop intent without changing that outcome or its cost.
            if (!"NEEDS_RECONCILIATION".equals(s(row, "state")) || writersQuiescent(row))
              return view(row);
            db.update(
                "UPDATE task SET cancel_requested_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                identity.tenant(),
                task);
            event(row, "cancel", "task.cancel_requested", null);
            return view(locked(identity.tenant(), task));
          }
          db.update(
              "UPDATE task SET cancel_requested_at=CURRENT_TIMESTAMP(6), state='CANCEL_REQUESTED' WHERE tenant_id=? AND id=?",
              identity.tenant(),
              task);
          event(row, "cancel", "task.cancel_requested", null);
          if (questionTask(row)) closeQuestions(row, "CANCELLED");
          if ("QUEUED".equals(s(row, "state")) || questionTask(row) && writersQuiescent(row))
            terminal(row, "CANCELLED", "CANCELLED");
          return view(locked(identity.tenant(), task));
        });
  }

  @Override
  public List<Event> events(Identity identity, String task, long after, int limit) {
    return tx(
        () -> {
          var row = visible(identity, task);
          access(row);
          if (after > n(row, "next_event_seq")) throw new TaskFailure(400, "FUTURE_CURSOR");
          long oldest =
              db.queryForObject(
                  "SELECT COALESCE(MIN(seq),0) FROM event WHERE tenant_id=? AND task_id=? AND occurred_at>CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY",
                  Long.class,
                  identity.tenant(),
                  task);
          if ((oldest > 0 && after < oldest - 1) || (oldest == 0 && n(row, "next_event_seq") > 0))
            throw new TaskFailure(410, "CURSOR_EXPIRED");
          return rows(
                  "SELECT * FROM event WHERE tenant_id=? AND task_id=? AND seq>? ORDER BY seq LIMIT ?",
                  identity.tenant(),
                  task,
                  after,
                  Math.min(limit, 64))
              .stream()
              .map(
                  e ->
                      new Event(
                          s(e, "event_id"),
                          n(e, "seq"),
                          s(e, "type"),
                          task,
                          time(e, "occurred_at"),
                          s(e, "code"),
                          s(e, "status"),
                          e.get("artifact_id") == null ? List.of() : List.of(s(e, "artifact_id"))))
              .toList();
        });
  }

  @Override
  public Download download(Identity identity, String artifactId) {
    return tx(
        () -> {
          identity(identity);
          var row =
              one(
                  "SELECT a.* FROM artifact a JOIN task t ON a.tenant_id=t.tenant_id AND a.task_id=t.id WHERE a.tenant_id=? AND a.id=? AND t.owner_actor_id=? AND a.state IN ('PUBLISHED','EXPIRED')",
                  identity.tenant(),
                  artifactId,
                  identity.actor());
          access(visible(identity, s(row, "task_id")));
          if (time(row, "expires_at").isBefore(Instant.now()))
            throw new TaskFailure(410, "ARTIFACT_EXPIRED");
          return new Download(artifact(row), identity.tenant(), s(row, "snapshot_key"));
        });
  }

  private long storageHeld(Map<String, Object> budget) {
    return n(budget, "storage_held") + n(budget, "media_checkpoint_held");
  }

  private void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String quality) {
    db.update(
        "INSERT INTO reservation(tenant_id,id,task_id,dispatch_id,category,amount,state,usage_quality) VALUES(?,?,?,?,?,?,?,?)",
        tenant,
        reservation,
        task,
        dispatch,
        category,
        amount,
        state,
        quality);
  }

  private void event(Map<String, Object> task, String fact, String type, String code) {
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    if (!rows(
            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
            tenant,
            taskId,
            fact)
        .isEmpty()) return;
    if (todoTask(task) || directoryTask(task) || JdbcSessionRecords.task(task)) {
      long seq =
          db.queryForObject(
              "SELECT next_event_seq FROM task WHERE tenant_id=? AND id=?",
              Long.class,
              tenant,
              taskId);
      boolean reserved =
          type.equals("session_history.content_deleted")
              || type.equals("working_directory.content_deleted")
              || type.equals("todo.content_deleted")
              || type.equals("task.completed")
              || type.equals("task.failed")
              || type.equals("task.cancelled")
              || type.equals("task.needs_reconciliation");
      if (seq >= (reserved ? 256 : 254))
        throw new TaskFailure(
            429,
            JdbcSessionRecords.task(task)
                ? "SESSION_HISTORY_LIMIT"
                : directoryTask(task) ? "DIRECTORY_EXECUTION_LIMIT" : "TODO_EXECUTION_LIMIT");
    }
    db.update(
        "UPDATE task SET next_event_seq=next_event_seq+1, revision=revision+1, updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
        tenant,
        taskId);
    long seq =
        db.queryForObject(
            "SELECT next_event_seq FROM task WHERE tenant_id=? AND id=?",
            Long.class,
            tenant,
            taskId);
    db.update(
        "INSERT INTO event(tenant_id,task_id,seq,event_id,fact_id,type,code,status,artifact_id) VALUES(?,?,?,?,?,?,?,?,?)",
        tenant,
        taskId,
        seq,
        taskId + ":" + seq,
        fact,
        type,
        code,
        db.queryForObject(
            "SELECT state FROM task WHERE tenant_id=? AND id=?", String.class, tenant, taskId),
        "artifact.created".equals(type) ? fact.substring("published:".length()) : null);
    sessionFact(task, fact);
    if (JdbcSessionRecords.task(task))
      sessions.account(
          task, fact.equals("terminal") || type.equals("session_history.content_deleted"));
    if (directoryTask(task))
      directoryMetadata(
          task, type.equals("working_directory.content_deleted") || fact.equals("terminal"));
    if (todoTask(task))
      todoMetadata(task, type.equals("todo.content_deleted") || fact.equals("terminal"));
  }

  @Override
  public Optional<Lease> claim(String worker) {
    maintainQuestions();
    for (String tenant :
        db.queryForList("SELECT tenant_id FROM tenant_budget ORDER BY tenant_id", String.class)) {
      // Select without a lock, then recheck this exact candidate under the shared run lock.
      // Every worker transaction takes run -> tenant -> task, including recovery claims.
      // Exclude unconfirmed directory writers before LIMIT so they cannot starve runnable work.
      // Mirror writersQuiescent here; the locked check below remains the takeover authority.
      var ready =
          rows(
              "SELECT id,evaluation_run_id FROM task t WHERE tenant_id=? AND record_version IN (1,2,3) AND (state='QUEUED' OR (state='RESUME_READY' AND NOT EXISTS(SELECT 1 FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id WHERE a.tenant_id=t.tenant_id AND a.task_id=t.id AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE)) AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.tenant_id=t.tenant_id AND r.id=CONCAT(t.id,':run') AND r.state='RESERVED')) OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6) AND (FIND_IN_SET('working_directory',t.capabilities)=0 OR (NOT EXISTS(SELECT 1 FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id WHERE a.tenant_id=t.tenant_id AND a.task_id=t.id AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE)) AND NOT EXISTS(SELECT 1 FROM native_search_run n WHERE n.tenant_id=t.tenant_id AND n.task_id=t.id AND n.phase<>'STOPPED'))))) ORDER BY ready_at,id LIMIT 1",
              tenant);
      if (ready.isEmpty()) continue;
      var candidate = ready.getFirst();
      Optional<Lease> lease =
          tx(
              () -> {
                var evaluation =
                    evaluation(
                        tenant,
                        executionScopes.targetRun(tenant, s(candidate, "evaluation_run_id")));
                var budget = bucket(tenant);
                var candidates =
                    rows(
                        "SELECT *,deadline>CURRENT_TIMESTAMP(6) AS within_deadline FROM task WHERE tenant_id=? AND id=? AND record_version IN (1,2,3) AND (state IN ('QUEUED','RESUME_READY') OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))) FOR UPDATE SKIP LOCKED",
                        tenant,
                        s(candidate, "id"));
                if (candidates.isEmpty()) return Optional.empty();
                var task = candidates.getFirst();
                if (!java.util.Objects.equals(
                    candidate.get("evaluation_run_id"), task.get("evaluation_run_id")))
                  return Optional
                      .empty(); // Retry under the correct parent lock; never change lock order.
                String taskId = s(task, "id");
                if ((directoryTask(task) || JdbcSessionRecords.task(task))
                    && "RUNNING".equals(s(task, "state"))
                    && !writersQuiescent(task)) return Optional.empty();
                boolean queued = "QUEUED".equals(s(task, "state"));
                boolean resume = "RESUME_READY".equals(s(task, "state"));
                if (resume
                    && (!writersQuiescent(task)
                        || !rows(
                                "SELECT id FROM reservation WHERE tenant_id=? AND id=? AND state='RESERVED'",
                                tenant,
                                taskId + ":run")
                            .isEmpty())) return Optional.empty();
                if ((queued || resume) && n(budget, "running") >= n(budget, "running_limit"))
                  return Optional.empty();
                // An expired writer might have caused an effect. Never infer safety from an absent
                // result.
                if (!rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                        tenant,
                        taskId)
                    .isEmpty()) {
                  if (modelRejection(task) == null && runRejection(evaluation) == null) {
                    try {
                      requireScope(tenant, evaluation, task, null);
                    } catch (TaskFailure failure) {
                      // Legacy unbound writers still require reconciliation. A claimed corrupt
                      // proof must first durably stop the shared run, before any recovery writes.
                      if (!failure.code().equals("EVALUATION_SCOPE_LEGACY_UNBOUND")) throw failure;
                    }
                  }
                  db.update(
                      "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND task_id=? AND state='RUNNING'",
                      tenant,
                      taskId);
                  terminal(task, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
                  return Optional.empty();
                }
                String rejection = modelRejection(task);
                if (rejection == null) rejection = runRejection(evaluation);
                if (rejection != null) {
                  terminal(task, "FAILED", rejection);
                  return Optional.empty();
                }
                try {
                  requireScope(tenant, evaluation, task, null);
                } catch (TaskFailure failure) {
                  if (List.of("EVALUATION_SCOPE_MISMATCH", "BUSINESS_POLICY_CONFLICT")
                      .contains(failure.code())) throw failure;
                  terminal(task, "FAILED", failure.code());
                  return Optional.empty();
                }
                if (!queued)
                  db.update(
                      "UPDATE task_attempt SET state='EXPIRED',ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='ACTIVE'",
                      tenant,
                      s(task, "current_attempt_id"));
                if (n(task, "within_deadline") != 1) {
                  terminal(task, "FAILED", "DEADLINE_EXCEEDED");
                  return Optional.empty();
                }
                try {
                  access(task);
                  if (JdbcSessionRecords.task(task)) {
                    sessionAdmission(task);
                    if (db.queryForObject(
                            "SELECT COUNT(*) FROM task_attempt WHERE tenant_id=? AND task_id=?",
                            Long.class,
                            tenant,
                            taskId)
                        >= 8) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
                  }
                  if (directoryTask(task)) {
                    directoryAdmission(task);
                    if (db.queryForObject(
                            "SELECT COUNT(*) FROM task_attempt WHERE tenant_id=? AND task_id=?",
                            Long.class,
                            tenant,
                            taskId)
                        >= 8) throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
                  }
                  if (todoTask(task)) {
                    if (!todosEnabled) throw new TaskFailure(403, "TODO_FEATURE_DISABLED");
                    models.requireTodo(snapshot(task));
                    if (n(todoProjection(task), "revision") < 0)
                      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
                    if (db.queryForObject(
                            "SELECT COUNT(*) FROM task_attempt WHERE tenant_id=? AND task_id=?",
                            Long.class,
                            tenant,
                            taskId)
                        >= 8) throw new TaskFailure(429, "TODO_EXECUTION_LIMIT");
                  }
                  if (questionTask(task) && !questionsEnabled)
                    throw new TaskFailure(403, "QUESTION_FEATURE_DISABLED");
                  if (resume) {
                    snapshot(task);
                    questionStorage(task, 131072);
                    todoStorage(task, 131072);
                  }
                } catch (TaskFailure e) {
                  terminal(task, "FAILED", e.code());
                  return Optional.empty();
                }
                if (task.get("cancel_requested_at") != null) {
                  terminal(task, "CANCELLED", "CANCELLED");
                  return Optional.empty();
                }
                String attempt = id();
                long fence = n(task, "fence") + 1;
                if (queued) {
                  db.update(
                      "UPDATE tenant_budget SET queued=queued-1,running=running+1 WHERE tenant_id=?",
                      tenant);
                  db.update(
                      "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                      tenant,
                      taskId + ":queue");
                  reserve(tenant, taskId, taskId + ":run", null, "RUNNING", 1, "RESERVED", "KNOWN");
                }
                if (resume) {
                  if (db.update(
                          "UPDATE reservation SET state='RESERVED' WHERE tenant_id=? AND id=? AND state='RELEASED'",
                          tenant,
                          taskId + ":run")
                      != 1) throw new TaskFailure(409, "RESERVATION_STATE");
                  db.update("UPDATE tenant_budget SET running=running+1 WHERE tenant_id=?", tenant);
                }
                db.update(
                    "INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state) VALUES(?,?,?,?,?,'ACTIVE')",
                    tenant,
                    attempt,
                    taskId,
                    worker,
                    fence);
                db.update(
                    "UPDATE task SET state='RUNNING',fence=?,current_attempt_id=?,worker_id=?,lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE tenant_id=? AND id=?",
                    fence,
                    attempt,
                    worker,
                    tenant,
                    taskId);
                event(task, "attempt:" + fence, "task.started", null);
                return Optional.of(
                    new Lease(
                        tenant,
                        s(task, "owner_actor_id"),
                        taskId,
                        attempt,
                        fence,
                        worker,
                        time(task, "deadline"),
                        s(task, "goal"),
                        caps(task),
                        n(task, "installation_epoch")));
              });
      if (lease.isPresent()) return lease;
    }
    return Optional.empty();
  }

  private Map<String, Object> fenced(Lease lease, boolean admission) {
    // Hold through the committing transaction: STOPPED cannot race a cached admission,
    // publication or successful finish. Settlement of previously admitted effects stays allowed.
    var evaluation = evaluation(lease, admission);
    bucket(lease.tenant());
    var task = locked(lease.tenant(), lease.taskId());
    if (n(task, "fence") != lease.fence()
        || !lease.attemptId().equals(s(task, "current_attempt_id"))
        || !lease.worker().equals(s(task, "worker_id"))
        || n(task, "live") != 1
        || !List.of("RUNNING", "CANCEL_REQUESTED").contains(s(task, "state")))
      throw new TaskFailure(409, "FENCE_REJECTED");
    if (admission) {
      String rejection = modelRejection(task);
      if (rejection == null) rejection = runRejection(evaluation);
      if (rejection != null) throw new TaskFailure(409, rejection);
      requireScope(lease.tenant(), evaluation, task, null);
      checkMaterials(task);
      access(task);
      if (JdbcSessionRecords.task(task)) {
        if (!lease.actor().equals(s(task, "owner_actor_id")))
          throw new TaskFailure(409, "INVOCATION_MISMATCH");
        sessionAdmission(task);
      }
      if (directoryTask(task)) {
        if (!lease.actor().equals(s(task, "owner_actor_id")))
          throw new TaskFailure(409, "INVOCATION_MISMATCH");
        directoryAdmission(task);
      }
      if (todoTask(task)) {
        if (!lease.actor().equals(s(task, "owner_actor_id")))
          throw new TaskFailure(409, "INVOCATION_MISMATCH");
        if (!todosEnabled) throw new TaskFailure(403, "TODO_FEATURE_DISABLED");
        if ("PURGED".equals(s(todoProjection(task), "content_state")))
          throw new TaskFailure(410, "TODO_CONTENT_EXPIRED");
        models.requireTodo(snapshot(task));
      }
      if (questionTask(task) && !questionsEnabled)
        throw new TaskFailure(403, "QUESTION_FEATURE_DISABLED");
      if (task.get("cancel_requested_at") != null) throw new TaskFailure(409, "CANCEL_REQUESTED");
      if (n(task, "within_deadline") != 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
    }
    if (db.update(
            "UPDATE task SET revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND revision=? AND current_attempt_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6)",
            lease.tenant(),
            lease.taskId(),
            n(task, "revision"),
            lease.attemptId(),
            lease.fence())
        != 1) throw new TaskFailure(409, "FENCE_REJECTED");
    if (JdbcSessionRecords.task(task)
        && Long.toString(n(task, "revision")).length()
            != Long.toString(n(task, "revision") + 1).length()) sessions.account(task, false);
    return task;
  }

  private EvaluationMaterials.Material material(Map<String, Object> task) {
    if (task.get("evaluation_snapshot") == null) return null;
    String json = s(task, "material_snapshot");
    EvaluationMaterials.require(
        json != null && Payloads.hash(json).equals(s(task, "material_digest")));
    var stored = Payloads.read(json, EvaluationMaterials.Material.class);
    var binding = Payloads.read(s(task, "evaluation_snapshot"), EvaluationPlans.Binding.class);
    var expected =
        EvaluationMaterials.load(EvaluationPlans.plan(binding.planId()), binding.sample());
    EvaluationMaterials.require(
        expected.equals(stored)
            && stored.goal().equals(s(task, "goal"))
            && Payloads.hash(
                    stored
                        .systemTemplate()
                        .replace("{{HOST_REPORT_TIME}}", time(task, "report_time").toString()))
                .equals(s(task, "material_system_digest")));
    return stored;
  }

  private void checkMaterials(Map<String, Object> task) {
    var material = material(task);
    if (material == null) return;
    for (var invocation :
        rows(
            "SELECT input_snapshot,output_snapshot,output_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='web_fetch' AND state='SUCCEEDED'",
            s(task, "tenant_id"),
            s(task, "id"))) {
      String output = s(invocation, "output_snapshot");
      EvaluationMaterials.require(
          output != null && Payloads.hash(output).equals(s(invocation, "output_digest")));
      var input =
          Payloads.read(
              s(invocation, "input_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
      EvaluationMaterials.source(material, input.path("url").asText(), output);
    }
    if (task.get("material_file_snapshot") != null) {
      var initial = initialFile(task);
      boolean written = false;
      for (var invocation :
          rows(
              "SELECT i.tool,i.output_snapshot,i.output_digest FROM invocation i JOIN step s ON s.tenant_id=i.tenant_id AND s.id=i.step_id WHERE i.tenant_id=? AND i.task_id=? AND i.state='SUCCEEDED' AND i.tool IN ('read','write') ORDER BY s.ordinal,i.slot",
              s(task, "tenant_id"),
              s(task, "id"))) {
        if (s(invocation, "tool").equals("write")) {
          written = true;
          continue;
        }
        if (written) continue;
        String output = s(invocation, "output_snapshot");
        EvaluationMaterials.require(Payloads.hash(output).equals(s(invocation, "output_digest")));
        var read = Payloads.read(output, com.fasterxml.jackson.databind.JsonNode.class);
        if (material.initialContent() != null) {
          var visible = new StringBuilder();
          String[] lines = material.initialContent().split("\n", -1);
          for (int index = 0; index < lines.length; index++)
            visible.append(index + 1).append(": ").append(lines[index]).append('\n');
          EvaluationMaterials.require(
              read.path("offset").asInt() == 1
                  && read.path("content").asText().equals(visible.toString()));
        }
        EvaluationMaterials.require(
            read.path("filePath").asText().equals(initial.path())
                && read.path("version").asLong() == initial.version()
                && read.path("size").asLong() == initial.size()
                && java.util.Objects.equals(
                    read.path("sha256").isNull() ? null : read.path("sha256").asText(),
                    initial.sha256()));
      }
    }
  }

  @Override
  public Optional<String> evaluationFile(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var material = material(task);
          if (material == null) return Optional.empty();
          boolean written =
              !rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND tool='write' AND state='SUCCEEDED' LIMIT 1",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty();
          if (written) {
            initialFile(task);
            return Optional.empty();
          }
          // A restart before first dispatch may inspect again, but may never replace its original
          // receipt.
          return Optional.of(material.target());
        });
  }

  private ReportFile initialFile(Map<String, Object> task) {
    String json = s(task, "material_file_snapshot");
    EvaluationMaterials.require(
        json != null && Payloads.hash(json).equals(s(task, "material_file_digest")));
    return Payloads.read(json, ReportFile.class);
  }

  @Override
  public void bindEvaluationFile(Lease lease, ReportFile file) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var material = material(task);
          EvaluationMaterials.require(material != null && material.target().equals(file.path()));
          String content = material.initialContent();
          EvaluationMaterials.require(
              content == null
                  ? file.version() == 0 && file.sha256() == null && file.size() == 0
                  : file.version() > 0
                      && Payloads.hash(content).equals(file.sha256())
                      && content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                          == file.size());
          if (task.get("material_file_snapshot") != null)
            EvaluationMaterials.require(initialFile(task).equals(file));
          else {
            EvaluationMaterials.require(
                rows(
                        "SELECT id FROM step WHERE tenant_id=? AND task_id=? LIMIT 1",
                        lease.tenant(),
                        lease.taskId())
                    .isEmpty());
            String json = Payloads.json(file);
            db.update(
                "UPDATE task SET material_file_snapshot=?,material_file_digest=? WHERE tenant_id=? AND id=?",
                json,
                Payloads.hash(json),
                lease.tenant(),
                lease.taskId());
          }
          return null;
        });
  }

  private void materialBody(Map<String, Object> task, String request, int ordinal) {
    var material = material(task);
    boolean business = businessTask(task);
    boolean media = n(task, "record_version") == 3;
    if (material == null && !business && !media) return;
    var wire = Payloads.read(request, com.fasterxml.jackson.databind.JsonNode.class);
    if (material != null) {
      initialFile(task);
      EvaluationMaterials.body(
          material, wire.path("requestBody").asText(), time(task, "report_time").toString());
    }
    // Step N's request contains exactly the confirmed history through N-1. Its own
    // outputs may already be committed on recovery and must not enter this prefix.
    var preceding =
        rows(
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND ordinal<? ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"),
            ordinal);
    EvaluationMaterials.require(ordinal > 0 && preceding.size() == ordinal - 1);
    var body =
        Payloads.read(
            wire.path("requestBody").asText(), com.fasterxml.jackson.databind.JsonNode.class);
    var expected = Payloads.read("[]", com.fasterxml.jackson.databind.node.ArrayNode.class);
    if (business || media) {
      if (caps(task).contains("present")) {
        var reports = new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy();
        var context = new ReportContext(time(task, "report_time"), List.of());
        String instruction = body.path("messages").path(0).path("content").asText();
        // Recovery retains the original fixed template; no model-selected instruction is allowed.
        EvaluationMaterials.require(
            media
                ? new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ImageReportComposer()
                    .instruction(context)
                    .equals(instruction)
                : tools(task).pins().containsKey("compose_report")
                    ? new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
                        .instruction(context)
                        .equals(instruction)
                    : List.of(reports.instruction(context), reports.currentInstruction(context))
                        .contains(instruction));
        if (!preceding.isEmpty()) {
          String first = s(preceding.getFirst(), "request_snapshot");
          EvaluationMaterials.require(
              Payloads.hash(first).equals(s(preceding.getFirst(), "request_digest")));
          var original = Payloads.read(first, com.fasterxml.jackson.databind.JsonNode.class);
          var originalBody =
              Payloads.read(
                  original.path("requestBody").asText(),
                  com.fasterxml.jackson.databind.JsonNode.class);
          EvaluationMaterials.require(
              instruction.equals(originalBody.path("messages").path(0).path("content").asText()));
        }
        expected.addObject().put("role", "system").put("content", instruction);
      }
      expected.addObject().put("role", "user").put("content", s(task, "goal"));
      if (media)
        expected
            .addObject()
            .put("role", "user")
            .put(
                "content",
                io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.directory(
                    images.snapshot(task)));
    } else {
      expected.add(body.path("messages").get(0));
      expected.add(body.path("messages").get(1));
    }
    int position = 0;
    var seen = new java.util.HashSet<String>();
    for (var prior : preceding) {
      String response = s(prior, "response_snapshot");
      EvaluationMaterials.require(
          n(prior, "ordinal") == ++position
              && response != null
              && Payloads.hash(response).equals(s(prior, "response_digest")));
      var original = Payloads.read(response, com.fasterxml.jackson.databind.JsonNode.class);
      EvaluationMaterials.require(
          original.path("version").asInt() == 2 && original.path("calls").isArray());
      var assistant = expected.addObject().put("role", "assistant");
      assistant.put("content", original.path("content").asText(""));
      var invocations =
          rows(
              "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? ORDER BY CAST(SUBSTRING_INDEX(slot,':',1) AS UNSIGNED)",
              s(task, "tenant_id"),
              s(task, "id"),
              s(prior, "id"));
      EvaluationMaterials.require(invocations.size() == original.path("calls").size());
      int slot = 0;
      for (var call : original.path("calls")) {
        var invocation = invocations.get(slot);
        String input = s(invocation, "input_snapshot");
        String output = s(invocation, "output_snapshot");
        EvaluationMaterials.require(
            (slot + ":" + call.path("id").asText()).equals(s(invocation, "slot"))
                && call.path("id").asText().equals(s(invocation, "provider_id"))
                && call.path("name").asText().equals(s(invocation, "tool"))
                && call.path("type").asText().equals("function")
                && call.path("arguments").asText().equals(input)
                && Payloads.hash(input).equals(s(invocation, "input_digest"))
                && s(invocation, "state").equals("SUCCEEDED")
                && output != null
                && Payloads.hash(output).equals(s(invocation, "output_digest")));
        var emitted = assistant.withArray("tool_calls").addObject();
        emitted.put("id", call.path("id").asText()).put("type", "function");
        emitted
            .putObject("function")
            .put("name", call.path("name").asText())
            .put("arguments", input);
        slot++;
      }
      for (var invocation : invocations)
        expected
            .addObject()
            .put("role", "tool")
            .put("tool_call_id", s(invocation, "provider_id"))
            .put("content", s(invocation, "output_snapshot"));
      if (media)
        for (var image : images.step(task, s(prior, "id"))) {
          var ref = image.result();
          if (seen.add(
              io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.identity(ref)))
            expected.add(
                io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.provider(
                    ref,
                    imageStore.read(
                        s(task, "tenant_id"),
                        image.key(),
                        ref.image().sha256(),
                        ref.image().size())));
        }
    }
    EvaluationMaterials.require(expected.equals(body.path("messages")));
    if (media) {
      var cp =
          Payloads.read(
              request,
              io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
      if (cp.version() != 3
          || request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12582912
          || !Payloads.hash(cp.requestBody()).equals(cp.bodyDigest())
          || !ModelProfiles.digest(snapshot(task)).equals(cp.configDigest()))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      // Every serialized reference is reloaded from this task and projected into exact data bytes.
      var definitions =
          new java.util.ArrayList<org.springframework.ai.tool.definition.ToolDefinition>();
      int toolIndex = 0;
      for (String name : caps(task)) {
        var pin = tools(task).pins().get(name);
        try {
          String schema;
          if (name.equals("web_fetch"))
            schema =
                new String(
                    io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader
                        .resource("input.schema.json"),
                    java.nio.charset.StandardCharsets.UTF_8);
          else {
            var contract =
                new io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts()
                    .load(name, pin.version());
            if (!contract.descriptor().outputSha256().equals(pin.digest()))
              throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
            schema = contract.input();
          }
          var saved = body.path("tools").path(toolIndex++).path("function");
          String description =
              io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts.description(
                  name);
          if (!saved.path("name").asText().equals(name)
              || !saved.path("description").asText().equals(description)
              || !saved
                  .path("parameters")
                  .equals(Payloads.read(schema, com.fasterxml.jackson.databind.JsonNode.class)))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          definitions.add(
              org.springframework.ai.tool.definition.ToolDefinition.builder()
                  .name(name)
                  .description(description)
                  .inputSchema(schema)
                  .build());
        } catch (java.io.IOException invalid) {
          throw new TaskFailure(503, "PLUGIN_SNAPSHOT_UNAVAILABLE");
        }
      }
      String projected =
          new io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol()
              .body(
                  snapshot(task),
                  cp.messages(),
                  definitions,
                  ref -> {
                    var object = images.object(task, ref);
                    return imageStore.read(
                        s(task, "tenant_id"),
                        object.key(),
                        ref.image().sha256(),
                        ref.image().size());
                  });
      if (!projected.equals(cp.requestBody())) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      models.checkInput(snapshot(task), projected);
    }
  }

  private String runRejection(Map<String, Object> evaluation) {
    return evaluation != null && !"OPEN".equals(s(evaluation, "state"))
        ? "MODEL_BUDGET_EXCEEDED"
        : null;
  }

  // The dispatch fact is the execution decision, including rows written before this fix.
  // Fixture failures may retry; a paid failed dispatch must retain its original rejection.
  private String modelRejection(Map<String, Object> task) {
    var rejected =
        rows(
            "SELECT code FROM model_dispatch WHERE tenant_id=? AND task_id=? AND (state IN ('BOUND_EXCEEDED','USAGE_UNKNOWN') OR (state='FAILED' AND currency IS NOT NULL)) ORDER BY started_at,id LIMIT 1",
            s(task, "tenant_id"),
            s(task, "id"));
    return rejected.isEmpty() ? null : s(rejected.getFirst(), "code");
  }

  @Override
  public boolean renew(Lease lease) {
    return db.update(
            "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE tenant_id=? AND id=? AND current_attempt_id=? AND worker_id=? AND fence=? AND lease_until>CURRENT_TIMESTAMP(6) AND state IN ('RUNNING','CANCEL_REQUESTED')",
            lease.tenant(),
            lease.taskId(),
            lease.attemptId(),
            lease.worker(),
            lease.fence())
        == 1;
  }

  @Override
  public Optional<Step> checkpoint(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          return rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId())
              .stream()
              .map(
                  row -> {
                    materialBody(task, s(row, "request_snapshot"), (int) n(row, "ordinal"));
                    return step(row);
                  })
              .findFirst();
        });
  }

  private Step step(Map<String, Object> row) {
    if (!"RETAINED".equals(s(row, "request_cleanup_state")))
      throw new TaskFailure(410, "CHECKPOINT_MEDIA_PURGED");
    if (n(row, "record_version") != 1) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    if (row.get("response_snapshot") != null
        && !Payloads.hash(s(row, "response_snapshot")).equals(s(row, "response_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!Payloads.hash(s(row, "request_snapshot")).equals(s(row, "request_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return new Step(
        s(row, "id"),
        (int) n(row, "ordinal"),
        s(row, "request_snapshot"),
        s(row, "response_snapshot"),
        s(row, "dispatch_id"));
  }

  private ModelSnapshot snapshot(Map<String, Object> task) {
    var snapshot = historicalSnapshot(task);
    models.available(snapshot);
    return snapshot;
  }

  private ModelSnapshot historicalSnapshot(Map<String, Object> task) {
    String value = s(task, "model_snapshot");
    if (value == null) throw new TaskFailure(409, "MODEL_SNAPSHOT_MISSING");
    if (!Payloads.hash(value).equals(s(task, "model_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ModelSnapshot.class);
    return snapshot;
  }

  @Override
  public ModelSnapshot modelSnapshot(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var evaluation = evaluation(lease);
          var task = fenced(lease, true);
          var snapshot = snapshot(task);
          checkEvaluation(task, evaluation, snapshot);
          return snapshot;
        });
  }

  private ToolSnapshot tools(Map<String, Object> task) {
    String value = s(task, "tools_snapshot");
    if (value == null) throw new TaskFailure(409, "PLUGIN_SNAPSHOT_UNAVAILABLE");
    if (!Payloads.hash(value).equals(s(task, "tools_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ToolSnapshot.class);
    if (snapshot.schemaVersion() != 1) throw new TaskFailure(409, "PLUGIN_SNAPSHOT_UNSUPPORTED");
    if (!snapshot.pins().keySet().equals(new java.util.HashSet<>(caps(task)))
        || snapshot.pins().values().stream().mapToLong(PluginPin::installationEpoch).sum()
            != n(task, "installation_epoch")) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return snapshot;
  }

  @Override
  public ToolSnapshot toolSnapshot(Lease lease) {
    return leaseTx(lease, () -> tools(fenced(lease, true)));
  }

  // This is always the first lock when a transaction can mutate evaluation accounting.
  private Map<String, Object> evaluation(Lease lease) {
    return evaluation(lease, true);
  }

  private Map<String, Object> evaluation(Lease lease, boolean admission) {
    String run =
        s(
            one(
                "SELECT evaluation_run_id FROM task WHERE tenant_id=? AND id=?",
                lease.tenant(),
                lease.taskId()),
            "evaluation_run_id");
    return evaluation(
        lease.tenant(), admission ? executionScopes.targetRun(lease.tenant(), run) : run);
  }

  private Map<String, Object> evaluation(String tenant, String run) {
    if (run == null) return null;
    var found =
        rows("SELECT * FROM evaluation_budget WHERE tenant_id=? AND id=? FOR UPDATE", tenant, run);
    if (found.isEmpty()) throw new TaskFailure(503, "MODEL_EVALUATION_UNAVAILABLE");
    return found.getFirst();
  }

  private EvaluationPlans.Plan plan(Map<String, Object> evaluation) {
    if (evaluation == null || evaluation.get("plan_snapshot") == null) return null;
    String json = s(evaluation, "plan_snapshot");
    if (!Payloads.hash(json).equals(s(evaluation, "plan_digest")))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    var stored = Payloads.read(json, EvaluationPlans.Plan.class);
    var canonical = EvaluationPlans.plan(stored.id());
    if (!stored.equals(canonical)
        || n(evaluation, "policy_version") != 2
        || n(evaluation, "max_requests") > canonical.maxRequests()
        || n(evaluation, "max_cost_micros") > canonical.maxCostMicros())
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    if (!rows(
            "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_snapshot IS NOT NULL AND (JSON_UNQUOTE(JSON_EXTRACT(evaluation_snapshot,'$.planDigest'))<>? OR (evaluation_phase='B' AND JSON_UNQUOTE(JSON_EXTRACT(evaluation_snapshot,'$.qualificationDigest'))<>COALESCE(?,''))) LIMIT 1",
            s(evaluation, "tenant_id"),
            s(evaluation, "id"),
            s(evaluation, "plan_digest"),
            s(evaluation, "phase_b_digest"))
        .isEmpty()) throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    return canonical;
  }

  private EvaluationPlans.Binding bindEvaluation(
      Map<String, Object> evaluation, ModelSnapshot model) {
    var selection = models.evaluationSelection();
    var plan = plan(evaluation);
    if (plan == null && selection == null) return null;
    if (plan == null || selection == null || !plan.equals(selection.plan()) || !model.paid())
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    // Same validation as profile admission, including the strictly local test seam.
    models.checkEvaluationModel(plan, model);
    boolean initial =
        rows(
                "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_snapshot IS NOT NULL LIMIT 1",
                s(evaluation, "tenant_id"),
                s(evaluation, "id"))
            .isEmpty();
    if (initial && "OPEN".equals(s(evaluation, "state"))) {
      var tenant = bucket(s(evaluation, "tenant_id"));
      if (n(evaluation, "requests_used") + plan.maxRequests() > n(evaluation, "max_requests")
          || plan.matrixWorstCostMicros()
              > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
          || plan.matrixWorstCostMicros() > n(tenant, "cost_limit") - n(tenant, "cost_held")
          || plan.tenantTokenLimit() > n(tenant, "token_limit") - n(tenant, "token_held")
          || plan.maxRequests() > n(tenant, "model_limit") - n(tenant, "model_used"))
        throw new TaskFailure(429, "EVALUATION_MATRIX_BUDGET_UNAVAILABLE");
    }
    if (model.stream() != List.of("W2", "W4").contains(selection.sample()))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
    String qualification = null;
    if (selection.phase().equals("B")) {
      if (!"OPEN".equals(s(evaluation, "state"))
          || !rows(
                  "SELECT r.id FROM reservation r JOIN task t ON t.tenant_id=r.tenant_id AND t.id=r.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? AND r.state='HELD_UNKNOWN' LIMIT 1",
                  s(evaluation, "tenant_id"),
                  s(evaluation, "id"))
              .isEmpty()) throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
      var q =
          EvaluationPlans.qualify(
              plan, s(evaluation, "phase_b_snapshot"), s(evaluation, "phase_b_digest"));
      if (!q.originalRunId().equals(s(evaluation, "id")) || !q.finalists().contains(model.model()))
        throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
      var screening =
          rows(
              "SELECT evaluation_model,evaluation_sample,state FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_phase='A'",
              s(evaluation, "tenant_id"),
              s(evaluation, "id"));
      for (String candidate : plan.candidates().keySet())
        for (String sample : List.of("W1", "W2")) {
          var match =
              screening.stream()
                  .filter(
                      t ->
                          candidate.equals(s(t, "evaluation_model"))
                              && sample.equals(s(t, "evaluation_sample")))
                  .toList();
          if (match.size() != 1
              || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(match.getFirst(), "state"))
              || q.finalists().contains(candidate)
                  && !"SUCCEEDED".equals(s(match.getFirst(), "state")))
            throw new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
        }
      if (rows(
              "SELECT id FROM task WHERE tenant_id=? AND evaluation_run_id=? AND evaluation_phase='B' LIMIT 1",
              s(evaluation, "tenant_id"),
              s(evaluation, "id"))
          .isEmpty()) {
        long rounds = 12L * q.finalists().size();
        long remainingCost =
            q.finalists().stream()
                .mapToLong(candidate -> 2 * EvaluationPlans.taskCost(plan, candidate, "W3"))
                .sum();
        var tenant = bucket(s(evaluation, "tenant_id"));
        if (n(evaluation, "requests_used") + rounds > n(evaluation, "max_requests")
            || remainingCost > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
            || remainingCost > n(tenant, "cost_limit") - n(tenant, "cost_held")
            || rounds * 1004096 > n(tenant, "token_limit") - n(tenant, "token_held")
            || rounds > n(tenant, "model_limit") - n(tenant, "model_used"))
          throw new TaskFailure(429, "EVALUATION_MATRIX_BUDGET_UNAVAILABLE");
      }
      qualification = s(evaluation, "phase_b_digest");
    }
    return new EvaluationPlans.Binding(
        plan.id(),
        s(evaluation, "plan_digest"),
        selection.phase(),
        selection.sample(),
        model.model(),
        qualification,
        n(evaluation, "max_requests"),
        n(evaluation, "max_cost_micros"));
  }

  private void checkEvaluation(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model) {
    if (businessTask(task)) {
      requireBusiness(s(task, "tenant_id"), evaluation, task, model);
      return;
    }
    var configured = bindEvaluation(evaluation, model);
    if (task.get("evaluation_snapshot") == null) {
      if (configured != null) throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
      return;
    }
    String json = s(task, "evaluation_snapshot");
    if (!Payloads.hash(json).equals(s(task, "evaluation_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var binding = Payloads.read(json, EvaluationPlans.Binding.class);
    if (!binding.equals(configured)
        || !binding.phase().equals(s(task, "evaluation_phase"))
        || !binding.sample().equals(s(task, "evaluation_sample"))
        || !binding.model().equals(s(task, "evaluation_model")))
      throw new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
  }

  private void admitPlanDispatch(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model, long cost) {
    var plan = plan(evaluation);
    String phase = s(task, "evaluation_phase"), sample = s(task, "evaluation_sample");
    var usage =
        rows(
            "SELECT t.evaluation_phase,t.evaluation_sample,t.evaluation_model,COUNT(*) AS calls,COALESCE(SUM(COALESCE(d.actual_cost,d.reserved_cost)),0) AS cost FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? GROUP BY t.evaluation_phase,t.evaluation_sample,t.evaluation_model",
            s(task, "tenant_id"),
            s(evaluation, "id"));
    long phaseCalls = 0,
        phaseCost = 0,
        modelCalls = 0,
        modelCost = 0,
        phaseModelCalls = 0,
        phaseModelCost = 0,
        sampleCalls = 0;
    for (var row : usage) {
      boolean sameModel = model.model().equals(s(row, "evaluation_model"));
      if (sameModel) {
        modelCalls += n(row, "calls");
        modelCost += n(row, "cost");
      }
      if (phase.equals(s(row, "evaluation_phase"))) {
        phaseCalls += n(row, "calls");
        phaseCost += n(row, "cost");
        if (sameModel) {
          phaseModelCalls += n(row, "calls");
          phaseModelCost += n(row, "cost");
        }
      }
      if (sameModel && sample.equals(s(row, "evaluation_sample"))) sampleCalls += n(row, "calls");
    }
    int modelPhaseLimit = phase.equals("A") ? 13 : 12;
    long phaseLimit = phase.equals("A") ? plan.phaseACostMicros() : plan.phaseBCostMicros();
    if (phaseCalls >= (phase.equals("A") ? 13 * plan.candidates().size() : 24)
        || phaseCost + cost > phaseLimit
        || modelCalls >= 25
        || modelCost + cost > 25 * cost
        || phaseModelCalls >= modelPhaseLimit
        || phaseModelCost + cost > modelPhaseLimit * cost
        || sampleCalls >= (sample.equals("W1") ? 7 : 6)
        || !rows(
                "SELECT d.id FROM model_dispatch d JOIN task t ON t.tenant_id=d.tenant_id AND t.id=d.task_id WHERE t.tenant_id=? AND t.evaluation_run_id=? AND d.state='DISPATCHED' LIMIT 1",
                s(task, "tenant_id"),
                s(evaluation, "id"))
            .isEmpty()) throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
  }

  @Override
  public Step prepare(Lease lease, String request) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var previous =
              rows(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                  lease.tenant(),
                  lease.taskId());
          if (!previous.isEmpty() && request.equals(s(previous.getFirst(), "request_snapshot"))) {
            materialBody(task, request, (int) n(previous.getFirst(), "ordinal"));
            return step(previous.getFirst());
          }
          int ordinal = previous.isEmpty() ? 1 : (int) n(previous.getFirst(), "ordinal") + 1;
          materialBody(task, request, ordinal);
          if (!previous.isEmpty()
              && (previous.getFirst().get("response_snapshot") == null
                  || !rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND step_id=? AND state<>'SUCCEEDED'",
                          lease.tenant(),
                          s(previous.getFirst(), "id"))
                      .isEmpty())) throw new TaskFailure(409, "CHECKPOINT_INCOMPLETE");
          if (questionTask(task)) {
            if (request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
              throw new TaskFailure(429, "MODEL_INPUT_TOO_LARGE");
            questionStorage(task, request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
          }
          if (todoTask(task)) {
            var wire =
                Payloads.read(
                    request,
                    io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
            if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(request)
                    > 131072
                || io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(
                        wire.requestBody())
                    > 65536) throw new TaskFailure(429, "MODEL_INPUT_TOO_LARGE");
            todoStorage(
                task,
                io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(request));
          }
          if (directoryTask(task)) directoryContext(task, request);
          String stepId = id();
          if (n(task, "record_version") == 3) {
            var budget = bucket(lease.tenant());
            long bytes = request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > n(budget, "storage_limit") - storageHeld(budget))
              throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            db.update(
                "UPDATE tenant_budget SET media_checkpoint_held=media_checkpoint_held+? WHERE tenant_id=?",
                bytes,
                lease.tenant());
            reserve(
                lease.tenant(),
                lease.taskId(),
                stepId + ":media-checkpoint",
                null,
                "MEDIA_CHECKPOINT",
                bytes,
                "SETTLED",
                "KNOWN");
            db.update(
                "UPDATE reservation SET actual=amount WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId + ":media-checkpoint");
          }
          db.update(
              "INSERT INTO step(tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,model_version) VALUES(?,?,?,?,?,'PREPARED',?,?,?)",
              lease.tenant(),
              stepId,
              lease.taskId(),
              lease.attemptId(),
              ordinal,
              request,
              Payloads.hash(request),
              snapshot(task).model());
          if (JdbcSessionRecords.task(task)) sessions.prepare(task, request, stepId);
          if (directoryTask(task)) {
            var p = directoryProjection(task);
            db.update(
                "UPDATE step SET directory_revision=?,directory_cwd=?,directory_binding_digest=? WHERE tenant_id=? AND id=?",
                n(p, "revision"),
                s(p, "cwd"),
                s(p, "binding_digest"),
                lease.tenant(),
                stepId);
          }
          if (questionTask(task)) {
            var wire =
                Payloads.read(
                    request,
                    io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
            for (var pending : questionMessages(task).entrySet()) {
              if (wire.messages().stream()
                  .anyMatch(
                      m -> m.role().equals("user") && pending.getValue().equals(m.content()))) {
                if (db.update(
                        "UPDATE user_question SET delivery_state='CHECKPOINTED',delivery_step_id=? WHERE tenant_id=? AND id=? AND delivery_state='PENDING'",
                        stepId,
                        lease.tenant(),
                        pending.getKey())
                    != 1) throw new TaskFailure(409, "QUESTION_DELIVERY_CONFLICT");
              }
            }
            db.update(
                "UPDATE task SET question_continuation_step_id=NULL WHERE tenant_id=? AND id=?",
                lease.tenant(),
                lease.taskId());
          }
          event(task, "prepared:" + stepId, "model.prepared", null);
          if (directoryTask(task) || JdbcSessionRecords.task(task)) todoCommitFence(lease);
          return new Step(stepId, ordinal, request, null, null);
        });
  }

  @Override
  public String dispatchModel(Lease lease, String stepId) {
    return leaseTx(
        lease,
        () -> {
          var evaluation = evaluation(lease);
          var task = fenced(lease, true);
          var model = snapshot(task);
          models.admit(model);
          var budget = bucket(lease.tenant());
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  lease.taskId());
          if (JdbcSessionRecords.task(task)) {
            sessions.validateStep(task, step);
            sessions.metadata(task, false, 4096);
          }
          if (step.get("response_snapshot") != null)
            throw new TaskFailure(409, "ALREADY_RESPONDED");
          if (directoryTask(task)) {
            var p = directoryProjection(task);
            if (step.get("directory_revision") == null
                || n(step, "directory_revision") != n(p, "revision")
                || !s(p, "cwd").equals(s(step, "directory_cwd"))
                || !s(p, "binding_digest").equals(s(step, "directory_binding_digest")))
              throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
            if (n(task, "models_used") >= 8)
              throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
            directoryStorage(task, 131072);
          }
          var wire =
              Payloads.read(
                  s(step, "request_snapshot"), com.fasterxml.jackson.databind.JsonNode.class);
          String bodyDigest = Payloads.hash(wire.path("requestBody").asText());
          if (!bodyDigest.equals(wire.path("bodyDigest").asText()))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          if (!ModelProfiles.digest(model).equals(wire.path("configDigest").asText()))
            throw new TaskFailure(409, "MODEL_CONFIG_UNAVAILABLE");
          checkEvaluation(task, evaluation, model);
          materialBody(task, s(step, "request_snapshot"), (int) n(step, "ordinal"));
          models.checkInput(model, wire.path("requestBody").asText());
          long upper =
              model.paid()
                  ? model.price().inputTokenUpper() + model.maxOutputTokens()
                  : s(step, "request_snapshot")
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                          .length
                      + 4096L;
          long cost =
              model.paid()
                  ? ModelCosts.amount(
                      model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                  : 0;
          if (model.paid()
              && (evaluation == null
                  || !"OPEN".equals(s(evaluation, "state"))
                  || n(evaluation, "requests_used") >= n(evaluation, "max_requests")
                  || cost > n(evaluation, "max_cost_micros") - n(evaluation, "cost_held")
                  || cost > n(task, "max_cost_micros") - n(task, "cost_held")
                  || cost > n(budget, "cost_limit") - n(budget, "cost_held")))
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          if (n(step, "dispatches") >= (model.paid() ? 1 : 2)
              || n(task, "models_used") >= n(task, "max_models")
              || n(budget, "model_used") >= n(budget, "model_limit")
              || n(budget, "token_held") + upper > n(budget, "token_limit")
              || n(task, "token_held") + upper > n(task, "max_tokens"))
            throw new TaskFailure(429, "MODEL_BUDGET_EXCEEDED");
          if (task.get("evaluation_snapshot") != null)
            admitPlanDispatch(task, evaluation, model, cost);
          questionStorage(task, 131072);
          todoStorage(task, 131072);
          String dispatch = id();
          if (model.paid()) {
            db.update(
                "UPDATE evaluation_budget SET requests_used=requests_used+1,cost_held=cost_held+? WHERE tenant_id=? AND id=?",
                cost,
                lease.tenant(),
                s(evaluation, "id"));
            db.update(
                "UPDATE tenant_budget SET cost_held=cost_held+? WHERE tenant_id=?",
                cost,
                lease.tenant());
            db.update(
                "UPDATE task SET cost_held=cost_held+? WHERE tenant_id=? AND id=?",
                cost,
                lease.tenant(),
                lease.taskId());
            reserve(
                lease.tenant(),
                lease.taskId(),
                dispatch + ":cost",
                dispatch,
                "MODEL_COST",
                cost,
                "HELD_UNKNOWN",
                "UNKNOWN");
          }
          db.update(
              "INSERT INTO model_dispatch(tenant_id,id,task_id,step_id,config_digest,request_digest,state,price_version,currency,reserved_cost) VALUES(?,?,?,?,?,?,'DISPATCHED',?,?,?)",
              lease.tenant(),
              dispatch,
              lease.taskId(),
              stepId,
              s(task, "model_digest"),
              bodyDigest,
              model.price() == null ? null : model.price().version(),
              model.paid() ? "CNY" : null,
              model.paid() ? cost : null);
          db.update(
              "UPDATE tenant_budget SET model_used=model_used+1,token_held=token_held+? WHERE tenant_id=?",
              upper,
              lease.tenant());
          db.update(
              "UPDATE task SET models_used=models_used+1,token_held=token_held+? WHERE tenant_id=? AND id=?",
              upper,
              lease.tenant(),
              lease.taskId());
          if (step.get("dispatch_id") != null)
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND dispatch_id=?",
                lease.tenant(),
                s(step, "dispatch_id"));
          reserve(
              lease.tenant(),
              lease.taskId(),
              dispatch,
              dispatch,
              "MODEL",
              upper,
              "RESERVED",
              "UNKNOWN");
          db.update(
              "UPDATE step SET state='RUNNING',dispatch_id=?,dispatches=dispatches+1 WHERE tenant_id=? AND id=?",
              dispatch,
              lease.tenant(),
              stepId);
          event(task, "model:" + dispatch, "model.dispatched", null);
          if (directoryTask(task)) todoCommitFence(lease);
          return dispatch;
        });
  }

  @Override
  public void modelResponded(
      Lease lease,
      String stepId,
      String dispatch,
      String response,
      List<Call> calls,
      Integer tokens,
      ModelUsage usage) {
    tx(
        () -> {
          var evaluation = evaluation(lease, false);
          var task = fenced(lease, false);
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  lease.taskId());
          if (!dispatch.equals(s(step, "dispatch_id")) || step.get("response_snapshot") != null)
            throw new TaskFailure(409, "DISPATCH_REJECTED");
          if (settleCost(lease, task, evaluation, dispatch, usage)) {
            String rejection =
                s(
                    one(
                        "SELECT code FROM model_dispatch WHERE tenant_id=? AND id=?",
                        lease.tenant(),
                        dispatch),
                    "code");
            db.update(
                "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId);
            event(task, "response:" + stepId, "model.rejected", rejection);
            return null;
          }
          if (todoTask(task) || directoryTask(task) || JdbcSessionRecords.task(task)) {
            try {
              access(task);
              if (JdbcSessionRecords.task(task)) {
                sessionAdmission(task);
                sessions.response(task, response, calls);
              } else if (directoryTask(task)) directoryAdmission(task);
              else {
                if (!todosEnabled) throw new TaskFailure(403, "TODO_FEATURE_DISABLED");
                models.requireTodo(snapshot(task));
              }
              if (task.get("cancel_requested_at") != null)
                throw new TaskFailure(409, "CANCEL_REQUESTED");
              if (n(task, "within_deadline") != 1) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
              io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.unicode(response);
              long invocations =
                  db.queryForObject(
                      "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND task_id=?",
                      Long.class,
                      lease.tenant(),
                      lease.taskId());
              if (io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(response)
                      > 65536
                  || calls.size() > 4) throw new TaskFailure(409, "MODEL_RESPONSE_TOO_LARGE");
              if (invocations + calls.size() > 20)
                throw new TaskFailure(
                    429,
                    directoryTask(task) ? "DIRECTORY_EXECUTION_LIMIT" : "TODO_EXECUTION_LIMIT");
              if (invocations + calls.size() > n(task, "max_tools"))
                throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
              for (var call : calls) {
                if (JdbcSessionRecords.task(task)) {
                  sessions.contracts.input(call.input());
                } else if (directoryTask(task)) {
                  io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.unicode(
                      call.providerId());
                  if (call.providerId().isEmpty()
                      || call.providerId().length() > 256
                      || !caps(task).contains(call.tool()))
                    throw new TaskFailure(400, "TOOL_INPUT_INVALID");
                  if (call.tool().equals("working_directory")) directories.input(call.input());
                  else {
                    try {
                      directoryFileContract(call.tool())
                          .in()
                          .validate(directories.json(call.input(), 65535));
                    } catch (IllegalArgumentException invalid) {
                      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
                    }
                  }
                } else {
                  if (!"todo_write".equals(call.tool()))
                    throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
                  todos.input(call.input());
                }
              }
              directoryStorage(
                  task,
                  response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                      + calls.stream()
                          .mapToLong(
                              c ->
                                  c.input()
                                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                      .length)
                          .sum());
              if (JdbcSessionRecords.task(task))
                sessions.body(
                    task,
                    response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + calls.stream()
                            .mapToLong(
                                c ->
                                    c.input()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                        .length)
                            .sum());
              todoStorage(
                  task,
                  io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(response)
                      + calls.stream()
                          .mapToLong(
                              c ->
                                  io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts
                                      .bytes(c.input()))
                          .sum());
            } catch (TaskFailure rejected) {
              // A rejected tool payload does not erase the real, known model usage.
              long upper =
                  db.queryForObject(
                      "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                      Long.class,
                      lease.tenant(),
                      dispatch);
              if (tokens != null && tokens >= 0 && tokens <= upper) {
                db.update(
                    "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
                    upper - tokens,
                    lease.tenant());
                db.update(
                    "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
                    upper - tokens,
                    lease.tenant(),
                    lease.taskId());
                db.update(
                    "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
                    tokens,
                    lease.tenant(),
                    dispatch);
              } else
                db.update(
                    "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=?",
                    lease.tenant(),
                    dispatch);
              db.update(
                  "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                  lease.tenant(),
                  stepId);
              db.update(
                  "UPDATE model_dispatch SET code=? WHERE tenant_id=? AND id=?",
                  rejected.code(),
                  lease.tenant(),
                  dispatch);
              event(task, "response:" + stepId, "model.rejected", rejected.code());
              return null;
            }
          }
          if (questionTask(task)
              && (response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768
                  || calls.size() > 8)) {
            db.update(
                "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId);
            db.update(
                "UPDATE model_dispatch SET state='FAILED',code='MODEL_RESPONSE_TOO_LARGE' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                dispatch);
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=? AND state='RESERVED'",
                lease.tenant(),
                dispatch);
            event(task, "response:" + stepId, "model.rejected", "MODEL_RESPONSE_TOO_LARGE");
            return null;
          }
          questionStorage(
              task, response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2L);
          db.update(
              "UPDATE step SET state='RESPONDED',response_snapshot=?,response_digest=? WHERE tenant_id=? AND id=?",
              response,
              Payloads.hash(response),
              lease.tenant(),
              stepId);
          long upper =
              n(
                  one(
                      "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                      lease.tenant(),
                      dispatch),
                  "amount");
          if (tokens != null && tokens >= 0 && tokens <= upper) {
            db.update(
                "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
                upper - tokens,
                lease.tenant());
            db.update(
                "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
                upper - tokens,
                lease.tenant(),
                lease.taskId());
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
                tokens,
                lease.tenant(),
                dispatch);
          } else
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                dispatch);
          try {
            String rejection = modelRejection(task);
            if (rejection == null) rejection = runRejection(evaluation);
            if (rejection != null) throw new TaskFailure(409, rejection);
            requireScope(lease.tenant(), evaluation, task, null);
            for (Call call : calls)
              if (call.input().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
                throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          } catch (TaskFailure rejected) {
            db.update(
                "UPDATE step SET state='REJECTED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                stepId);
            db.update(
                "UPDATE model_dispatch SET code=? WHERE tenant_id=? AND id=?",
                rejected.code(),
                lease.tenant(),
                dispatch);
            event(task, "response:" + stepId, "model.rejected", rejected.code());
            return null; // Audit/usage committed; no executable tool intents from this response.
          }
          int ordinal =
              db.queryForObject(
                  "SELECT COUNT(*) FROM invocation WHERE tenant_id=? AND attempt_id=?",
                  Integer.class,
                  lease.tenant(),
                  lease.attemptId());
          long todoExpected = todoTask(task) ? n(todoProjection(task), "revision") : 0;
          for (Call call : calls) {
            if (!lease.capabilities().contains(call.tool()))
              throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
            var plugin = tools(task).pins().get(call.tool());
            db.update(
                "INSERT INTO invocation(tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
                lease.tenant(),
                id(),
                lease.taskId(),
                stepId,
                lease.attemptId(),
                ++ordinal,
                call.slot(),
                call.providerId(),
                id(),
                call.tool(),
                plugin.version(),
                plugin.digest(),
                plugin.installationEpoch(),
                call.input(),
                Payloads.hash(call.input()));
            if (todoTask(task))
              db.update(
                  "UPDATE invocation SET todo_expected_revision=? WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=?",
                  todoExpected++,
                  lease.tenant(),
                  lease.taskId(),
                  stepId,
                  call.slot());
          }
          if (JdbcSessionRecords.task(task)) sessions.bindCalls(task, stepId);
          event(task, "response:" + stepId, "model.responded", null);
          return null;
        });
    var outcome =
        one(
            "SELECT state,code FROM model_dispatch WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch);
    if (List.of("BOUND_EXCEEDED", "USAGE_UNKNOWN").contains(s(outcome, "state")))
      throw new TaskFailure(409, s(outcome, "code"));
    if (s(outcome, "code") != null) throw new TaskFailure(409, s(outcome, "code"));
  }

  private ModelSnapshot settlementModel(Lease lease, Map<String, Object> task, String dispatch) {
    var row =
        one(
            "SELECT * FROM model_dispatch WHERE tenant_id=? AND id=? AND task_id=?",
            lease.tenant(),
            dispatch,
            lease.taskId());
    if (!"DISPATCHED".equals(s(row, "state"))) throw new TaskFailure(409, "DISPATCH_REJECTED");
    try {
      var model = historicalSnapshot(task);
      if (!s(task, "model_digest").equals(s(row, "config_digest"))
          || !ModelProfiles.digest(model).equals(s(row, "config_digest"))
          || model.paid()
              && (model.price() == null
                  || !model.price().version().equals(s(row, "price_version"))
                  || !model.price().currency().equals(s(row, "currency"))
                  || ModelCosts.amount(
                          model.price(), model.price().inputTokenUpper(), model.maxOutputTokens())
                      != n(row, "reserved_cost")))
        throw new IllegalArgumentException("Historical price binding mismatch");
      return model;
    } catch (TaskFailure | IllegalArgumentException corrupt) {
      db.update(
          "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN',cost_quality='UNKNOWN' WHERE tenant_id=? AND dispatch_id=? AND state='RESERVED'",
          lease.tenant(),
          dispatch);
      if (task.get("evaluation_run_id") != null)
        db.update(
            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            s(task, "evaluation_run_id"));
      throw new TaskFailure(409, "MODEL_SETTLEMENT_PROOF_INVALID");
    }
  }

  private boolean settleCost(
      Lease lease,
      Map<String, Object> task,
      Map<String, Object> evaluation,
      String dispatch,
      ModelUsage usage) {
    var model = settlementModel(lease, task, dispatch);
    if (JdbcSessionRecords.task(task)) {
      String old =
          db.queryForObject(
              "SELECT usage_snapshot FROM model_dispatch WHERE tenant_id=? AND id=?",
              String.class,
              lease.tenant(),
              dispatch);
      sessions.body(
          task,
          io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                  Payloads.json(usage))
              - (old == null
                  ? 0
                  : io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                      old)));
    }
    Long actual = null;
    boolean exceeded = false;
    boolean unknown = model.paid() && (usage.inputTokens() == null || usage.outputTokens() == null);
    if (model.paid()) {
      exceeded =
          usage.inputTokens() != null && usage.inputTokens() > model.price().inputTokenUpper()
              || usage.outputTokens() != null && usage.outputTokens() > model.maxOutputTokens()
              || usage.totalTokens() != null
                  && java.math.BigInteger.valueOf(usage.totalTokens())
                          .compareTo(
                              java.math.BigInteger.valueOf(model.price().inputTokenUpper())
                                  .add(java.math.BigInteger.valueOf(model.maxOutputTokens())))
                      > 0;
      if (usage.inputTokens() != null && usage.outputTokens() != null) {
        try {
          actual = ModelCosts.amount(model.price(), usage.inputTokens(), usage.outputTokens());
        } catch (ArithmeticException invalidProviderCost) {
          // Retain raw usage; an unrepresentable cost is UNKNOWN, never clamped.
          exceeded = true;
        }
      }
      long reserved =
          n(
              one(
                  "SELECT reserved_cost FROM model_dispatch WHERE tenant_id=? AND id=?",
                  lease.tenant(),
                  dispatch),
              "reserved_cost");
      long delta = actual == null ? 0 : actual - reserved;
      exceeded = exceeded || actual != null && actual > reserved;
      db.update(
          "UPDATE evaluation_budget SET cost_held=cost_held+?,state=IF(?,'STOPPED',state) WHERE tenant_id=? AND id=?",
          delta,
          exceeded || unknown,
          lease.tenant(),
          s(evaluation, "id"));
      db.update(
          "UPDATE tenant_budget SET cost_held=cost_held+? WHERE tenant_id=?",
          delta,
          lease.tenant());
      db.update(
          "UPDATE task SET cost_held=cost_held+? WHERE tenant_id=? AND id=?",
          delta,
          lease.tenant(),
          lease.taskId());
      if (actual != null)
        db.update(
            "UPDATE reservation SET actual=?,state='SETTLED',usage_quality='KNOWN',cost_quality='KNOWN' WHERE tenant_id=? AND id=?",
            actual,
            lease.tenant(),
            dispatch + ":cost");
      else
        db.update(
            "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            dispatch + ":cost");
    }
    db.update(
        "UPDATE model_dispatch SET state=?,code=?,ended_at=CURRENT_TIMESTAMP(6),input_tokens=?,output_tokens=?,total_tokens=?,usage_snapshot=?,actual_cost=?,cost_quality=? WHERE tenant_id=? AND id=?",
        exceeded ? "BOUND_EXCEEDED" : unknown ? "USAGE_UNKNOWN" : "RESPONDED",
        exceeded ? "MODEL_COST_BOUND_EXCEEDED" : unknown ? "MODEL_USAGE_UNKNOWN" : null,
        usage.inputTokens(),
        usage.outputTokens(),
        usage.totalTokens(),
        Payloads.json(usage),
        actual,
        actual == null ? "UNKNOWN" : "KNOWN",
        lease.tenant(),
        dispatch);
    // Preserve the actual fact and stop subsequent dispatches even if the provider violates its
    // bound.
    if (exceeded)
      db.update(
          "UPDATE task SET max_models=models_used WHERE tenant_id=? AND id=?",
          lease.tenant(),
          lease.taskId());
    return exceeded || unknown;
  }

  @Override
  public void modelFailed(Lease lease, String dispatch, String code, ModelUsage usage) {
    tx(
        () -> {
          var evaluation = evaluation(lease, false);
          var task = fenced(lease, false);
          var model = settlementModel(lease, task, dispatch);
          if (usage != null) settleCost(lease, task, evaluation, dispatch, usage);
          else if (model.paid())
            settleCost(
                lease,
                task,
                evaluation,
                dispatch,
                new ModelUsage(null, null, null, null, null, null, null, null));
          db.update(
              "UPDATE model_dispatch SET state='FAILED',code=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state IN ('DISPATCHED','RESPONDED')",
              code,
              lease.tenant(),
              dispatch,
              lease.taskId());
          Long tokens = usage == null ? null : usage.totalTokens();
          long reserved =
              n(
                  one(
                      "SELECT amount FROM reservation WHERE tenant_id=? AND id=?",
                      lease.tenant(),
                      dispatch),
                  "amount");
          if (JdbcSessionRecords.task(task) && tokens != null && tokens <= reserved) {
            db.update(
                "UPDATE tenant_budget SET token_held=token_held-? WHERE tenant_id=?",
                reserved - tokens,
                lease.tenant());
            db.update(
                "UPDATE task SET token_held=token_held-? WHERE tenant_id=? AND id=?",
                reserved - tokens,
                lease.tenant(),
                lease.taskId());
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=?,usage_quality='KNOWN' WHERE tenant_id=? AND id=?",
                tokens,
                lease.tenant(),
                dispatch);
          } else {
            db.update(
                "UPDATE reservation SET state='HELD_UNKNOWN' WHERE tenant_id=? AND dispatch_id=? AND category='MODEL'",
                lease.tenant(),
                dispatch);
          }
          if (JdbcSessionRecords.task(task)) sessions.account(task, false);
          return null;
        });
  }

  @Override
  public Invocation admit(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest) {
    if (call.tool().equals("session_event_read"))
      throw new TaskFailure(409, "SESSION_READ_COMMIT_REQUIRED");
    if (call.tool().equals("working_directory"))
      throw new TaskFailure(409, "DIRECTORY_COMMIT_REQUIRED");
    if (call.tool().equals("todo_write")) throw new TaskFailure(409, "TODO_COMMIT_REQUIRED");
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          var budget = bucket(lease.tenant());
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND step_id=? AND slot=? AND task_id=?",
                  lease.tenant(),
                  stepId,
                  call.slot(),
                  lease.taskId());
          if (!Payloads.hash(call.input()).equals(s(invocation, "input_digest"))
              || !call.tool().equals(s(invocation, "tool"))
              || !call.providerId().equals(s(invocation, "provider_id")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (directoryTask(task)) {
            invocation = originalDirectorySlot(lease, stepId, call, pluginVersion, pluginDigest);
            freezeDirectorySlot(task, invocation);
          }
          var material = material(task);
          if (material != null) {
            initialFile(task);
            var input = Payloads.read(call.input(), com.fasterxml.jackson.databind.JsonNode.class);
            if (call.tool().equals("web_fetch"))
              EvaluationMaterials.require(
                  material.sources().containsKey(input.path("url").asText()));
            if (call.tool().equals("write"))
              EvaluationMaterials.require(
                  !rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND tool='read' AND state='SUCCEEDED' LIMIT 1",
                          lease.tenant(),
                          lease.taskId())
                      .isEmpty());
            if (call.tool().equals("read") || call.tool().equals("write"))
              EvaluationMaterials.require(
                  material.target().equals(input.path("file_path").asText()));
            if (call.tool().equals("present"))
              EvaluationMaterials.require(
                  input.path("files").size() == 1
                      && material
                          .target()
                          .equals(input.path("files").get(0).path("path").asText()));
          }
          String invocationId = s(invocation, "id");
          var pinned = tools(task).pins().get(call.tool());
          var installation =
              one(
                  "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                  lease.tenant(),
                  call.tool());
          if (!pluginVersion.equals(s(invocation, "plugin_version"))
              || !pluginDigest.equals(s(invocation, "plugin_digest"))
              || !pluginVersion.equals(pinned.version())
              || !pluginDigest.equals(pinned.digest())
              || n(invocation, "installation_epoch") != pinned.installationEpoch()
              || !pluginVersion.equals(s(installation, "plugin_version"))
              || !pluginDigest.equals(s(installation, "plugin_digest")))
            throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
          if ("SUCCEEDED".equals(s(invocation, "state"))) {
            if (!Payloads.hash(s(invocation, "output_snapshot"))
                .equals(s(invocation, "output_digest")))
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            return new Invocation(
                invocationId,
                s(invocation, "effect_intent_id"),
                "SUCCEEDED",
                s(invocation, "output_snapshot"));
          }
          if (!"PENDING".equals(s(invocation, "state")))
            throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
          if (n(task, "tools_used") >= n(task, "max_tools")
              || n(budget, "tool_used") >= n(budget, "tool_limit"))
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          db.update(
              "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              lease.taskId());
          db.update(
              "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "RESERVED",
              "KNOWN");
          db.update(
              "UPDATE invocation SET state='RUNNING' WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "intent:" + invocationId, "tool.dispatched", null);
          if (directoryTask(task)) todoCommitFence(lease);
          return new Invocation(invocationId, s(invocation, "effect_intent_id"), "RUNNING", null);
        });
  }

  @Override
  public void observeWebFetch(Lease lease, String invocationId, WebFetchObservation observation) {
    tx(
        () -> {
          if (observation.cancellation() != null) {
            observeWebCancellation(lease, invocationId, observation);
            return null;
          }
          var task = fenced(lease, false);
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (!"web_fetch".equals(invocation.get("tool"))
              || !"0.3.0".equals(invocation.get("plugin_version"))
              || !"RUNNING".equals(invocation.get("state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          boolean failed = observation.failure() != WebFetchObservation.Failure.NONE;
          event(
              task,
              (failed ? "web-failure:" : "web-phase:") + invocationId + ":" + observation.phase(),
              failed ? "web.fetch_failed" : "web.fetch_phase",
              observation.phase() + "_" + observation.failure());
          return null;
        });
  }

  /** Diagnostic append only: an expired original fence cannot authorize execution or settlement. */
  private void observeWebCancellation(
      Lease lease, String invocationId, WebFetchObservation observation) {
    var cancellation = observation.cancellation();
    var task =
        one(
            "SELECT * FROM task WHERE tenant_id=? AND id=? FOR UPDATE",
            lease.tenant(),
            lease.taskId());
    var bindings =
        rows(
            "SELECT i.id FROM invocation i JOIN task_attempt a ON a.tenant_id=i.tenant_id AND a.task_id=i.task_id AND a.id=i.attempt_id WHERE i.tenant_id=? AND i.task_id=? AND i.id=? AND i.attempt_id=? AND a.worker_id=? AND a.fence=? AND i.tool='web_fetch' AND i.plugin_version='0.3.0' AND i.state IN ('RUNNING','OUTCOME_UNKNOWN')",
            lease.tenant(),
            lease.taskId(),
            invocationId,
            lease.attemptId(),
            lease.worker(),
            lease.fence());
    if (bindings.size() != 1) throw new TaskFailure(409, "WEB_OBSERVATION_BINDING_REJECTED");
    String fact = "web-cancel:" + invocationId + ":" + lease.fence();
    if (!rows(
            "SELECT seq FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
            lease.tenant(),
            lease.taskId(),
            fact)
        .isEmpty()) return;
    event(task, fact, "web.fetch_cancelled", cancellation.phase() + "_" + cancellation.reason());
    event(
        task,
        fact + ":elapsed",
        "web.cancel_elapsed_ms",
        Long.toString(cancellation.elapsedMillis()));
    event(
        task,
        fact + ":checkpoint",
        "web.checkpoint_rejected",
        cancellation.checkpointCode() + ":" + cancellation.checkpointMillis());
    event(
        task,
        "web-failure:" + invocationId + ":" + observation.phase(),
        "web.fetch_failed",
        observation.phase() + "_" + observation.failure());
  }

  @Override
  public void preserveWebFetchResult(Lease lease, String invocationId, String output) {
    tx(
        () -> {
          var task = fenced(lease, false);
          var invocation =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (!"RUNNING".equals(invocation.get("state")))
            throw new TaskFailure(409, "INVOCATION_STATE");
          var step =
              one(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocation.get("step_id"));
          String digest = WebFetchReceipts.digest(task, step, invocation, output);
          var existing =
              rows(
                  "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                  lease.tenant(),
                  lease.taskId(),
                  "web-result:" + invocationId);
          if (!existing.isEmpty()) {
            if (!digest.equals(existing.getFirst().get("code"))
                || !output.equals(invocation.get("output_snapshot"))
                || !Payloads.hash(output).equals(invocation.get("output_digest")))
              throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
            return null;
          }
          if (invocation.get("output_snapshot") != null || invocation.get("output_digest") != null)
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
          db.update(
              "UPDATE invocation SET output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND task_id=? AND id=?",
              output,
              Payloads.hash(output),
              lease.tenant(),
              lease.taskId(),
              invocationId);
          event(task, "web-result:" + invocationId, "web.result_preserved", digest);
          return null;
        });
  }

  @Override
  public void toolSucceeded(Lease lease, String invocationId, String output) {
    rejectTodoBypass(lease, invocationId);
    commitTool(lease, invocationId, output, false);
  }

  private void commitTool(Lease lease, String invocationId, String output, boolean mcpCommit) {
    boolean rejected =
        tx(
            () ->
                directoryTransaction(
                    lease,
                    () -> {
                      var task = fenced(lease, false);
                      if (directoryTask(task)) {
                        task = fenced(lease, true);
                        if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                          throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
                        directoryStorage(
                            task, output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                      }
                      var producer =
                          one(
                              "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                              lease.tenant(),
                              lease.taskId(),
                              invocationId);
                      if (io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(
                              s(producer, "tool"))
                          && !mcpCommit) throw new TaskFailure(409, "MCP_COMMIT_REQUIRED");
                      if ("present".equals(s(producer, "tool")))
                        throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
                      if ("read_image".equals(s(producer, "tool"))) {
                        var committed =
                            rows(
                                "SELECT image_id FROM task_image_object WHERE tenant_id=? AND task_id=? AND producer_invocation=? AND phase='COMMITTED'",
                                lease.tenant(),
                                lease.taskId(),
                                invocationId);
                        var image =
                            Payloads.read(
                                output,
                                io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result.class);
                        if (committed.size() != 1
                            || !committed
                                .getFirst()
                                .get("image_id")
                                .equals(image.image().imageId()))
                          throw new TaskFailure(409, "IMAGE_COMMIT_REQUIRED");
                      }
                      var receipts =
                          rows(
                              "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                              lease.tenant(),
                              lease.taskId(),
                              "web-result:" + invocationId);
                      if (!receipts.isEmpty()) {
                        var step =
                            one(
                                "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                                lease.tenant(),
                                lease.taskId(),
                                producer.get("step_id"));
                        if (!output.equals(producer.get("output_snapshot"))
                            || !Payloads.hash(output).equals(producer.get("output_digest"))
                            || !WebFetchReceipts.digest(task, step, producer, output)
                                .equals(receipts.getFirst().get("code")))
                          throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
                      }
                      if (db.update(
                              "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                              output,
                              Payloads.hash(output),
                              lease.tenant(),
                              invocationId,
                              lease.taskId())
                          != 1) throw new TaskFailure(409, "INVOCATION_STATE");
                      db.update(
                          "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
                          lease.tenant(),
                          invocationId);
                      event(task, "result:" + invocationId, "tool.succeeded", null);
                      if (directoryTask(task)) todoCommitFence(lease);
                      try {
                        checkMaterials(task);
                        return false;
                      } catch (TaskFailure failure) {
                        if (!failure.code().equals("EVALUATION_MATERIAL_MISMATCH")) throw failure;
                        // Preserve the confirmed tool result and settlement, and stop the shared
                        // run
                        // atomically. A crash before WorkerLoop.stop cannot open another admission.
                        db.update(
                            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
                            lease.tenant(),
                            s(task, "evaluation_run_id"));
                        return true;
                      }
                    }));
    if (rejected) throw new TaskFailure(409, "EVALUATION_MATERIAL_MISMATCH");
  }

  @Override
  public void uncertain(Lease lease, String invocationId) {
    rejectTodoBypass(lease, invocationId);
    tx(
        () -> {
          var task = fenced(lease, false);
          db.update(
              "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
              lease.tenant(),
              invocationId,
              lease.taskId());
          db.update(
              "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND id=? AND state='RESERVED'",
              lease.tenant(),
              invocationId);
          terminal(task, "NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN");
          return null;
        });
  }

  @Override
  public boolean searchQuiescent(Lease lease, String invocation) {
    return rows(
            "SELECT root_invocation FROM native_search_run WHERE tenant_id=? AND task_id=? AND root_invocation=? AND phase<>'STOPPED'",
            lease.tenant(),
            lease.taskId(),
            invocation)
        .isEmpty();
  }

  @Override
  public void prepareSearch(
      Lease lease,
      Invocation invocation,
      String directory,
      String snapshot,
      String query,
      String pin) {
    leaseTx(
        lease,
        () -> {
          fenced(lease, true);
          var budget = bucket(lease.tenant());
          var call =
              one(
                  "SELECT * FROM invocation WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  invocation.id(),
                  lease.taskId());
          // A PROPOSED invocation can be admitted by a newer fenced attempt. Its original
          // proposal attempt is historical; the native run below records the current writer.
          if (!"RUNNING".equals(s(call, "state"))
              || !List.of("glob", "grep").contains(s(call, "tool"))
              || !invocation.effectId().equals(s(call, "effect_intent_id")))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var aggregate =
              one(
                  "SELECT COUNT(*) AS roots,COALESCE(SUM(COALESCE(result_size,4194304)),0) AS bytes FROM native_search_run WHERE tenant_id=? AND task_id=?",
                  lease.tenant(),
                  lease.taskId());
          if (n(aggregate, "roots") >= 20 || n(aggregate, "bytes") + 4194304 > 8388608)
            throw new TaskFailure(429, "SEARCH_RESULT_LIMIT");
          if (storageHeld(budget) + 4194304 > n(budget, "storage_limit"))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String host =
              s(
                  one(
                      "SELECT host_id FROM worker_process WHERE id=? AND stopped=FALSE",
                      lease.worker()),
                  "host_id");
          db.update(
              "INSERT INTO native_search_run(tenant_id,root_invocation,task_id,effect_id,attempt_id,worker_id,host_id,runtime_directory,snapshot_digest,query_digest,tool_pin) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
              lease.tenant(),
              invocation.id(),
              lease.taskId(),
              invocation.effectId(),
              lease.attemptId(),
              lease.worker(),
              host,
              directory,
              snapshot,
              query,
              pin);
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held+4194304 WHERE tenant_id=?",
              lease.tenant());
          reserve(
              lease.tenant(),
              lease.taskId(),
              "search:" + invocation.id(),
              null,
              "SEARCH_STORAGE",
              4194304,
              "RESERVED",
              "KNOWN");
          return null;
        });
  }

  @Override
  public void searchStarted(Lease lease, String invocation, NativeIdentity identity) {
    leaseTx(
        lease,
        () -> {
          fenced(lease, true);
          if (db.update(
                  "UPDATE native_search_run SET phase='STARTED',process_identity=? WHERE tenant_id=? AND task_id=? AND root_invocation=? AND attempt_id=? AND phase='PREPARED'",
                  Payloads.json(identity),
                  lease.tenant(),
                  lease.taskId(),
                  invocation,
                  lease.attemptId())
              != 1) throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          return null;
        });
  }

  @Override
  public void searchStopped(Run run, NativeIdentity identity) {
    // Append-only physical observation: an old fence may report death, never business success.
    tx(
        () -> {
          bucket(run.tenant());
          var task = locked(run.tenant(), run.task());
          var row =
              one(
                  "SELECT * FROM native_search_run WHERE tenant_id=? AND root_invocation=? AND task_id=? AND effect_id=? AND worker_id=? FOR UPDATE",
                  run.tenant(),
                  run.invocation(),
                  run.task(),
                  run.effect(),
                  run.worker());
          String fact = Payloads.json(identity);
          if (row.get("process_identity") != null && !fact.equals(row.get("process_identity")))
            throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          db.update(
              "INSERT IGNORE INTO native_search_stop(tenant_id,root_invocation,identity_digest,process_identity) VALUES(?,?,?,?)",
              run.tenant(),
              run.invocation(),
              Payloads.hash(fact),
              fact);
          db.update(
              "UPDATE native_search_run SET phase='STOPPED',process_identity=?,stopped_at=COALESCE(stopped_at,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND root_invocation=?",
              fact,
              run.tenant(),
              run.invocation());
          settleStoppedWriters(task);
          return null;
        });
  }

  @Override
  public void searchCompleted(
      Lease lease, String invocation, String hash, long size, Map<String, Integer> cursors) {
    if (size < 0 || size > 4194304 || !hash.matches("[a-f0-9]{64}") || cursors.size() > 100000)
      throw new TaskFailure(409, "SEARCH_RESULT_LIMIT");
    leaseTx(
        lease,
        () -> {
          fenced(lease, true);
          var run =
              one(
                  "SELECT * FROM native_search_run WHERE tenant_id=? AND task_id=? AND root_invocation=? AND attempt_id=? FOR UPDATE",
                  lease.tenant(),
                  lease.taskId(),
                  invocation,
                  lease.attemptId());
          if (!"STOPPED".equals(s(run, "phase")) || run.get("result_hash") != null)
            throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          db.update(
              "UPDATE native_search_run SET result_hash=?,result_size=?,cursor_index=? WHERE tenant_id=? AND root_invocation=?",
              hash,
              size,
              Payloads.json(cursors),
              lease.tenant(),
              invocation);
          db.update(
              "UPDATE reservation SET actual=?,state='SETTLED' WHERE tenant_id=? AND id=? AND category='SEARCH_STORAGE' AND state='RESERVED'",
              size,
              lease.tenant(),
              "search:" + invocation);
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
              4194304 - size,
              lease.tenant());
          return null;
        });
  }

  @Override
  public Result searchPage(Lease lease, String query, String pin, String cursor) {
    return leaseTx(
        lease,
        () -> {
          fenced(lease, true);
          for (var row :
              rows(
                  "SELECT * FROM native_search_run WHERE tenant_id=? AND task_id=? AND result_hash IS NOT NULL AND phase='STOPPED' AND expires_at>CURRENT_TIMESTAMP(6)",
                  lease.tenant(),
                  lease.taskId())) {
            var index =
                Payloads.read(
                    s(row, "cursor_index"), com.fasterxml.jackson.databind.JsonNode.class);
            if (!index.has(cursor)) continue;
            if (!query.equals(s(row, "query_digest")) || !pin.equals(s(row, "tool_pin")))
              throw new TaskFailure(409, "SEARCH_CURSOR_INVALID");
            return new Result(
                s(row, "root_invocation"),
                s(row, "runtime_directory"),
                s(row, "result_hash"),
                n(row, "result_size"),
                index.path(cursor).intValue());
          }
          throw new TaskFailure(409, "SEARCH_CURSOR_INVALID");
        });
  }

  @Override
  public List<Run> nativeSearches(String host) {
    return rows(
            "SELECT n.*, (t.state<>'RUNNING' OR t.deadline<=CURRENT_TIMESTAMP(6) OR t.lease_until<=CURRENT_TIMESTAMP(6) OR w.stopped=TRUE) AS stop_requested FROM native_search_run n JOIN task t ON t.tenant_id=n.tenant_id AND t.id=n.task_id JOIN worker_process w ON w.id=n.worker_id WHERE n.host_id=? AND n.phase<>'STOPPED' ORDER BY n.root_invocation LIMIT 64",
            host)
        .stream()
        .map(
            row ->
                new Run(
                    s(row, "tenant_id"),
                    s(row, "task_id"),
                    s(row, "root_invocation"),
                    s(row, "effect_id"),
                    s(row, "attempt_id"),
                    s(row, "worker_id"),
                    s(row, "runtime_directory"),
                    s(row, "snapshot_digest"),
                    s(row, "phase"),
                    row.get("process_identity") == null
                        ? null
                        : Payloads.read(s(row, "process_identity"), NativeIdentity.class),
                    ((Number) row.get("stop_requested")).intValue() != 0))
        .toList();
  }

  @Override
  public List<Run> expiredSearches(String host) {
    return rows(
            "SELECT n.* FROM native_search_run n JOIN task t ON t.tenant_id=n.tenant_id AND t.id=n.task_id WHERE n.host_id=? AND n.phase='STOPPED' AND n.storage_removed=FALSE AND n.expires_at<=CURRENT_TIMESTAMP(6) AND t.state IN ('SUCCEEDED','FAILED','CANCELLED','NEEDS_RECONCILIATION') AND NOT EXISTS(SELECT 1 FROM task_attempt a WHERE a.tenant_id=n.tenant_id AND a.task_id=n.task_id AND a.quiescent=FALSE) LIMIT 64",
            host)
        .stream()
        .map(
            row ->
                new Run(
                    s(row, "tenant_id"),
                    s(row, "task_id"),
                    s(row, "root_invocation"),
                    s(row, "effect_id"),
                    s(row, "attempt_id"),
                    s(row, "worker_id"),
                    s(row, "runtime_directory"),
                    s(row, "snapshot_digest"),
                    "STOPPED",
                    Payloads.read(s(row, "process_identity"), NativeIdentity.class),
                    true))
        .toList();
  }

  @Override
  public void searchStorageRemoved(Run run) {
    tx(
        () -> {
          bucket(run.tenant());
          locked(run.tenant(), run.task());
          var row =
              one(
                  "SELECT * FROM native_search_run WHERE tenant_id=? AND root_invocation=? AND task_id=? AND effect_id=? FOR UPDATE",
                  run.tenant(),
                  run.invocation(),
                  run.task(),
                  run.effect());
          if (!"STOPPED".equals(s(row, "phase")))
            throw new TaskFailure(409, "SEARCH_PROCESS_UNPROVEN");
          var reservation =
              one(
                  "SELECT * FROM reservation WHERE tenant_id=? AND id=? AND category='SEARCH_STORAGE' FOR UPDATE",
                  run.tenant(),
                  "search:" + run.invocation());
          if (!"RELEASED".equals(s(reservation, "state"))) {
            long held =
                reservation.get("actual") == null
                    ? n(reservation, "amount")
                    : n(reservation, "actual");
            db.update(
                "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                held,
                run.tenant());
            db.update(
                "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                run.tenant(),
                "search:" + run.invocation());
          }
          db.update(
              "UPDATE native_search_run SET storage_removed=TRUE,result_size=COALESCE(result_size,0) WHERE tenant_id=? AND root_invocation=?",
              run.tenant(),
              run.invocation());
          return null;
        });
  }

  private boolean publicReport(Map<String, Object> task) {
    if (directoryTask(task)) return false;
    var pins = tools(task).pins();
    var pin = pins.get("web_fetch");
    return pin != null && "0.3.0".equals(pin.version())
        || pins.keySet().stream()
            .anyMatch(name -> List.of("read", "write", "present", "compose_report").contains(name));
  }

  @Override
  public ReportContext reportContext(Lease lease) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (!publicReport(task) || time(task, "report_time") == null)
            throw new TaskFailure(409, "REPORT_METADATA_MISSING");
          return reportContext(task);
        });
  }

  private ReportContext reportContext(Map<String, Object> task) {
    boolean composed = tools(task).pins().containsKey("compose_report");
    var fetchPin = tools(task).pins().get("web_fetch");
    var sources =
        rows(
                "SELECT id,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='web_fetch' AND state='SUCCEEDED'"
                    + (composed ? "" : " AND plugin_version='0.3.0'")
                    + " ORDER BY ordinal",
                s(task, "tenant_id"),
                s(task, "id"))
            .stream()
            .map(
                row -> {
                  if (composed
                      && (fetchPin == null
                          || !"0.3.0".equals(fetchPin.version())
                          || !fetchPin.version().equals(s(row, "plugin_version"))
                          || !fetchPin.digest().equals(s(row, "plugin_digest"))))
                    throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
                  return new Source(
                      s(row, "id"), s(row, "output_snapshot"), s(row, "output_digest"));
                })
            .toList();
    return new ReportContext(time(task, "report_time"), sources);
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Lease lease, String invocation) {
    return leaseTx(lease, () -> imageReportContext(fenced(lease, true), invocation));
  }

  private io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Map<String, Object> task, String invocation) {
    var compose =
        one(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='compose_report'",
            s(task, "tenant_id"),
            s(task, "id"),
            invocation);
    var step =
        one(
            "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(compose, "step_id"));
    if (step.get("response_snapshot") == null
        || !Payloads.hash(s(step, "response_snapshot")).equals(step.get("response_digest"))
        || step.get("dispatch_id") == null)
      throw new TaskFailure(409, "REPORT_IMAGE_SOURCE_INVALID");
    var dispatch =
        one(
            "SELECT * FROM model_dispatch WHERE tenant_id=? AND task_id=? AND id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            s(step, "dispatch_id"));
    if (!"RESPONDED".equals(s(dispatch, "state"))
        || !Payloads.hash(s(step, "request_snapshot")).equals(step.get("request_digest")))
      throw new TaskFailure(409, "REPORT_IMAGE_SOURCE_INVALID");
    var checkpoint =
        Payloads.read(
            s(step, "request_snapshot"),
            io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
    if (checkpoint.version() != 3
        || !Payloads.hash(checkpoint.requestBody()).equals(checkpoint.bodyDigest())
        || !s(task, "model_digest").equals(checkpoint.configDigest()))
      throw new TaskFailure(409, "REPORT_IMAGE_SOURCE_INVALID");
    var body =
        Payloads.read(checkpoint.requestBody(), com.fasterxml.jackson.databind.JsonNode.class);
    var result =
        new java.util.ArrayList<
            io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ImageSource>();
    for (var message : checkpoint.messages())
      if (message.images() != null)
        for (var ref : message.images()) {
          var object = images.object(task, ref);
          byte[] bytes =
              imageStore.read(
                  s(task, "tenant_id"), object.key(), ref.image().sha256(), ref.image().size());
          var part =
              io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.provider(ref, bytes);
          boolean present = false;
          for (var actual : body.path("messages")) if (actual.equals(part)) present = true;
          if (!present) throw new TaskFailure(409, "REPORT_IMAGE_SOURCE_INVALID");
          var source =
              one(
                  "SELECT output_digest FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                  s(task, "tenant_id"),
                  s(task, "id"),
                  ref.sourceRef().substring(6));
          result.add(
              new io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ImageSource(
                  ref,
                  s(source, "output_digest"),
                  s(step, "id"),
                  s(step, "dispatch_id"),
                  checkpoint.configDigest(),
                  checkpoint.bodyDigest(),
                  s(step, "response_digest")));
        }
    return new io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence(
        reportContext(task),
        List.copyOf(result),
        !snapshot(task).paid() && models.localEvaluation(snapshot(task)));
  }

  private void requirePresent(Lease lease, Map<String, Object> task, String invocationId) {
    if (!publicReport(task)) throw new TaskFailure(409, "REPORT_POLICY_REQUIRED");
    var invocation =
        one(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
            lease.tenant(),
            lease.taskId(),
            invocationId);
    if (!"present".equals(s(invocation, "tool")) || !"RUNNING".equals(s(invocation, "state")))
      throw new TaskFailure(409, "INVOCATION_STATE");
  }

  @Override
  public List<Upload> stageReports(Lease lease, String invocationId, List<ReportFile> files) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          requirePresent(lease, task, invocationId);
          if (files.isEmpty()
              || files.size() > 4
              || files.stream().map(ReportFile::path).distinct().count() != files.size())
            throw new TaskFailure(400, "TOOL_INPUT_INVALID");
          var input =
              Payloads.read(
                  s(
                      one(
                          "SELECT input_snapshot FROM invocation WHERE tenant_id=? AND id=?",
                          lease.tenant(),
                          invocationId),
                      "input_snapshot"),
                  com.fasterxml.jackson.databind.JsonNode.class);
          var paths = new java.util.ArrayList<String>();
          input.path("files").forEach(file -> paths.add(file.path("path").asText()));
          if (!paths.equals(files.stream().map(ReportFile::path).toList()))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var result = new java.util.ArrayList<Upload>();
          for (var file : files) {
            requireComposition(task, file);
            if (file.version() < 1
                || file.size() < 1
                || file.size() > 1048576
                || !file.sha256().matches("[a-f0-9]{64}")
                || !"text/markdown; charset=utf-8".equals(file.mediaType()))
              throw new TaskFailure(400, "REPORT_INVALID");
            String receipt = Payloads.json(file), digest = Payloads.hash(receipt);
            var old =
                rows(
                    "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=? AND receipt_digest=?",
                    lease.tenant(),
                    lease.taskId(),
                    invocationId,
                    digest);
            if (!old.isEmpty()) {
              var a = old.getFirst();
              if (!receipt.equals(s(a, "file_receipt")))
                throw new TaskFailure(409, "ARTIFACT_MISMATCH");
              result.add(
                  new Upload(
                      s(a, "id"),
                      s(a, "upload_id"),
                      s(a, "state"),
                      file.sha256(),
                      file.size(),
                      s(a, "snapshot_key")));
              continue;
            }
            if (!rows(
                    "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation<>?",
                    lease.tenant(),
                    lease.taskId(),
                    invocationId)
                .isEmpty()) throw new TaskFailure(409, "REPORT_ALREADY_STAGED");
            var budget = bucket(lease.tenant());
            if (storageHeld(budget) + file.size() > n(budget, "storage_limit"))
              throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
            String artifact = id(), upload = id();
            db.update(
                "INSERT INTO artifact(tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,sha256,size,media_type,file_receipt,receipt_digest) VALUES(?,?,?,?,?,?,'STAGING',?,?,?,?,?)",
                lease.tenant(),
                artifact,
                lease.taskId(),
                invocationId,
                upload,
                lease.fence(),
                file.sha256(),
                file.size(),
                file.mediaType(),
                receipt,
                digest);
            db.update(
                "UPDATE tenant_budget SET storage_held=storage_held+? WHERE tenant_id=?",
                file.size(),
                lease.tenant());
            reserve(
                lease.tenant(),
                lease.taskId(),
                artifact,
                null,
                "STORAGE",
                file.size(),
                "RESERVED",
                "KNOWN");
            event(task, "staging:" + artifact, "artifact.staging", null);
            result.add(new Upload(artifact, upload, "STAGING", file.sha256(), file.size(), null));
          }
          if (rows(
                      "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=?",
                      lease.tenant(),
                      lease.taskId(),
                      invocationId)
                  .size()
              != files.size()) throw new TaskFailure(409, "ARTIFACT_MISMATCH");
          return List.copyOf(result);
        });
  }

  private void requireComposition(Map<String, Object> task, ReportFile file) {
    var pin = tools(task).pins().get("compose_report");
    if (pin == null) return;
    var confirmed =
        rows(
            "SELECT id,input_snapshot,input_digest,output_snapshot,output_digest,plugin_version,plugin_digest FROM invocation WHERE tenant_id=? AND task_id=? AND tool='compose_report' AND state='SUCCEEDED' ORDER BY ordinal",
            s(task, "tenant_id"),
            s(task, "id"));
    for (var row : confirmed) {
      String input = s(row, "input_snapshot"), output = s(row, "output_snapshot");
      if (!Payloads.hash(input).equals(s(row, "input_digest"))
          || output == null
          || !Payloads.hash(output).equals(s(row, "output_digest"))
          || !pin.version().equals(s(row, "plugin_version"))
          || !pin.digest().equals(s(row, "plugin_digest")))
        throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
      if (pin.version().equals("0.2.0")) {
        if (new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ImageReportComposer()
            .matches(input, output, file, imageReportContext(task, s(row, "id")))) return;
      } else if (new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
          .matches(input, output, file, reportContext(task))) return;
    }
    throw new TaskFailure(409, "REPORT_COMPOSITION_REQUIRED");
  }

  @Override
  public void presentSucceeded(
      Lease lease, String invocationId, List<Publication> publications, String output) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          requirePresent(lease, task, invocationId);
          var artifacts =
              rows(
                  "SELECT * FROM artifact WHERE tenant_id=? AND task_id=? AND producer_invocation=?",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          if (artifacts.size() != publications.size()
              || artifacts.isEmpty()
              || publications.stream().map(Publication::artifactId).distinct().count()
                  != publications.size()) throw new TaskFailure(409, "ARTIFACT_MISMATCH");
          var result =
              Payloads.read(output, com.fasterxml.jackson.databind.JsonNode.class).path("files");
          if (!result.isArray() || result.size() != publications.size())
            throw new TaskFailure(409, "TOOL_OUTPUT_INVALID");
          for (int index = 0; index < publications.size(); index++) {
            var pub = publications.get(index);
            var a =
                artifacts.stream()
                    .filter(row -> pub.artifactId().equals(s(row, "id")))
                    .findFirst()
                    .orElseThrow(() -> new TaskFailure(409, "ARTIFACT_MISMATCH"));
            var receipt = Payloads.read(s(a, "file_receipt"), ReportFile.class);
            var item = result.get(index);
            if (!"STAGING".equals(s(a, "state"))
                || !pub.key().equals(s(a, "upload_id") + "-" + s(a, "sha256"))
                || !Payloads.hash(s(a, "file_receipt")).equals(s(a, "receipt_digest"))
                || !pub.artifactId().equals(item.path("artifactId").asText())
                || !receipt.path().equals(item.path("path").asText())
                || receipt.version() != item.path("version").asLong()
                || !receipt.sha256().equals(item.path("sha256").asText())
                || receipt.size() != item.path("size").asLong()
                || !receipt.mediaType().equals(item.path("mediaType").asText()))
              throw new TaskFailure(409, "ARTIFACT_MISMATCH");
            db.update(
                "UPDATE artifact SET state='PUBLISHED',snapshot_key=?,production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                pub.key(),
                lease.fence(),
                lease.tenant(),
                pub.artifactId());
            db.update(
                "UPDATE reservation SET state='SETTLED',actual=amount WHERE tenant_id=? AND id=?",
                lease.tenant(),
                pub.artifactId());
            event(task, "published:" + pub.artifactId(), "artifact.created", null);
          }
          db.update(
              "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              output,
              Payloads.hash(output),
              lease.tenant(),
              invocationId);
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "result:" + invocationId, "tool.succeeded", null);
          return null;
        });
  }

  @Override
  public void toolFailed(Lease lease, String invocationId, String code) {
    tx(
        () -> {
          var task = fenced(lease, false);
          if (JdbcSessionRecords.task(task))
            throw new TaskFailure(409, "SESSION_READ_COMMIT_REQUIRED");
          if (db.update(
                  "UPDATE invocation SET state='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
                  lease.tenant(),
                  invocationId,
                  lease.taskId())
              != 1) throw new TaskFailure(409, "INVOCATION_STATE");
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
              lease.tenant(),
              invocationId);
          event(task, "failure:" + invocationId, "tool.failed", code);
          return null;
        });
  }

  @Override
  public Upload stage(Lease lease, String hash, long size) {
    return leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (publicReport(task)) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var budget = bucket(lease.tenant());
          var existing =
              rows(
                  "SELECT * FROM artifact WHERE tenant_id=? AND task_id=?",
                  lease.tenant(),
                  lease.taskId());
          if (!existing.isEmpty()) {
            var a = existing.getFirst();
            if (!hash.equals(s(a, "sha256")) || size != n(a, "size"))
              throw new TaskFailure(409, "ARTIFACT_MISMATCH");
            return new Upload(
                s(a, "id"), s(a, "upload_id"), s(a, "state"), hash, size, s(a, "snapshot_key"));
          }
          String producer =
              s(
                  one(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state='SUCCEEDED' ORDER BY ordinal DESC LIMIT 1",
                      lease.tenant(),
                      lease.taskId()),
                  "id");
          if (size > 1048576 || storageHeld(budget) + size > n(budget, "storage_limit"))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String artifact = id(), upload = id();
          db.update(
              "INSERT INTO artifact(tenant_id,id,task_id,producer_invocation,upload_id,production_fence,state,sha256,size) VALUES(?,?,?,?,?,?,'STAGING',?,?)",
              lease.tenant(),
              artifact,
              lease.taskId(),
              producer,
              upload,
              lease.fence(),
              hash,
              size);
          db.update(
              "UPDATE tenant_budget SET storage_held=storage_held+? WHERE tenant_id=?",
              size,
              lease.tenant());
          reserve(
              lease.tenant(), lease.taskId(), artifact, null, "STORAGE", size, "RESERVED", "KNOWN");
          event(task, "staging:" + artifact, "artifact.staging", null);
          return new Upload(artifact, upload, "STAGING", hash, size, null);
        });
  }

  @Override
  public void verified(Lease lease, String artifactId, String key) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          db.update(
              "UPDATE artifact SET state='VERIFIED',snapshot_key=?,production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='STAGING'",
              key,
              lease.fence(),
              lease.tenant(),
              artifactId,
              lease.taskId());
          event(task, "verified:" + artifactId, "artifact.verified", null);
          return null;
        });
  }

  @Override
  public void publish(Lease lease, String artifactId) {
    leaseTx(
        lease,
        () -> {
          var task = fenced(lease, true);
          if (publicReport(task)) throw new TaskFailure(409, "PRESENT_COMMIT_REQUIRED");
          var artifact =
              one(
                  "SELECT * FROM artifact WHERE tenant_id=? AND id=? AND task_id=?",
                  lease.tenant(),
                  artifactId,
                  lease.taskId());
          if ("PUBLISHED".equals(s(artifact, "state"))) return null;
          if (!"VERIFIED".equals(s(artifact, "state")) || artifact.get("snapshot_key") == null)
            throw new TaskFailure(409, "ARTIFACT_UNVERIFIED");
          db.update(
              "UPDATE artifact SET state='PUBLISHED',production_fence=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
              lease.fence(),
              lease.tenant(),
              artifactId);
          db.update(
              "UPDATE reservation SET state='SETTLED',actual=amount WHERE tenant_id=? AND id=?",
              lease.tenant(),
              artifactId);
          event(task, "published:" + artifactId, "artifact.created", null);
          return null;
        });
  }

  @Override
  public void finish(Lease lease, String summary) {
    boolean paused =
        leaseTx(
            lease,
            () -> {
              var task = fenced(lease, true);
              if (JdbcSessionRecords.task(task)) {
                var publicSummary = sessions.contracts.publicText(summary, sessions.sensitive());
                if (!publicSummary.content().equals(summary))
                  throw new TaskFailure(409, "SESSION_EVENT_UNSAFE");
                if (io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                        summary)
                    > 8192) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
                sessions.body(
                    task,
                    io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(
                        summary));
              }
              if (directoryTask(task)) {
                io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.unicode(
                    summary);
                long bytes = summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (bytes > 8192) throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
                directoryStorage(task, bytes);
              }
              if (todoTask(task)) {
                io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.unicode(summary);
                int bytes =
                    io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts.bytes(summary);
                if (bytes > 8192) throw new TaskFailure(409, "SUMMARY_TOO_LARGE");
                todoStorage(task, bytes);
              }
              if (questionTask(task)) {
                if (summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
                  throw new TaskFailure(409, "SUMMARY_TOO_LARGE");
                questionStorage(
                    task, summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                if (!questionMessages(task).isEmpty()) {
                  var latest =
                      one(
                          "SELECT id FROM step WHERE tenant_id=? AND task_id=? ORDER BY ordinal DESC LIMIT 1",
                          lease.tenant(),
                          lease.taskId());
                  questionPause(task, lease, "RESUME_READY", s(latest, "id"));
                  event(task, "question-final-resume:" + s(latest, "id"), "task.resumable", null);
                  return true;
                }
              }
              if (publicReport(task)
                  && rows(
                          "SELECT a.id FROM artifact a JOIN invocation i ON i.tenant_id=a.tenant_id AND i.id=a.producer_invocation AND i.task_id=a.task_id WHERE a.tenant_id=? AND a.task_id=? AND a.state='PUBLISHED' AND a.file_receipt IS NOT NULL AND i.tool='present' AND i.state='SUCCEEDED'",
                          lease.tenant(),
                          lease.taskId())
                      .isEmpty()) throw new TaskFailure(409, "REPORT_NOT_PUBLISHED");
              if (!rows(
                          "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state<>'SUCCEEDED'",
                          lease.tenant(),
                          lease.taskId())
                      .isEmpty()
                  || !rows(
                          "SELECT id FROM artifact WHERE tenant_id=? AND task_id=? AND state<>'PUBLISHED'",
                          lease.tenant(),
                          lease.taskId())
                      .isEmpty()) throw new TaskFailure(409, "UNRESOLVED_FACTS");
              db.update(
                  "UPDATE task SET summary=? WHERE tenant_id=? AND id=?",
                  summary,
                  lease.tenant(),
                  lease.taskId());
              terminal(task, "SUCCEEDED", null);
              return false;
            });
    if (paused)
      throw new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionSuspended();
  }

  @Override
  public void stop(Lease lease, String code) {
    tx(
        () -> {
          var task = fenced(lease, false);
          if (code.equals("EVALUATION_MATERIAL_MISMATCH") && task.get("evaluation_run_id") != null)
            db.update(
                "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
                lease.tenant(),
                s(task, "evaluation_run_id"));
          boolean pendingEffect =
              !rows(
                      "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                      lease.tenant(),
                      lease.taskId())
                  .isEmpty();
          if (pendingEffect
              || List.of("SNAPSHOT_CORRUPT", "MODEL_SETTLEMENT_PROOF_INVALID").contains(code))
            terminal(task, "NEEDS_RECONCILIATION", code);
          else
            terminal(task, task.get("cancel_requested_at") != null ? "CANCELLED" : "FAILED", code);
          return null;
        });
  }

  private void terminal(Map<String, Object> task, String state, String code) {
    if ("CANCELLED".equals(state) && !writersQuiescent(task)) return;
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    closeQuestions(task, code == null ? "TASK_COMPLETED" : code);
    db.update(
        "UPDATE task SET state=?,code=? WHERE tenant_id=? AND id=?", state, code, tenant, taskId);
    if ("NEEDS_RECONCILIATION".equals(state)) {
      db.update(
          "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE tenant_id=? AND task_id=? AND state='RUNNING'",
          tenant,
          taskId);
      db.update(
          "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND task_id=? AND category='TOOL' AND state='RESERVED'",
          tenant,
          taskId);
    }
    if (!"NEEDS_RECONCILIATION".equals(state)) {
      int queued =
          db.update(
              "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
              tenant,
              taskId + ":queue");
      db.update("UPDATE tenant_budget SET queued=queued-? WHERE tenant_id=?", queued, tenant);
    }
    if (writersQuiescent(task)) {
      releaseRun(task);
      releaseQuestionTask(task);
    }
    db.update(
        "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE tenant_id=? AND task_id=? AND category='MODEL' AND state='RESERVED'",
        tenant,
        taskId);
    db.update(
        "UPDATE task_attempt SET state=?,ended_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND state='ACTIVE'",
        "SUCCEEDED".equals(state) ? "SUCCEEDED" : "STOPPED",
        tenant,
        s(task, "current_attempt_id"));
    event(
        task,
        "terminal",
        "SUCCEEDED".equals(state)
            ? "task.completed"
            : "task." + state.toLowerCase(java.util.Locale.ROOT),
        code);
  }

  @Override
  public void registerWorker(
      String worker,
      String host,
      long pid,
      long startTicks,
      String bootId,
      String observerPidNamespace) {
    db.update(
        "INSERT INTO worker_process(id,host_id,pid,start_ticks,boot_id,observer_pid_namespace) VALUES(?,?,?,?,?,?)",
        worker,
        host,
        pid,
        startTicks,
        bootId,
        observerPidNamespace);
  }

  @Override
  public List<WorkerProcess> processes(String host) {
    return rows(
            "SELECT w.*,EXISTS(SELECT 1 FROM task_attempt a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id WHERE a.worker_id=w.id AND a.quiescent=FALSE AND (t.cancel_requested_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND OR (t.state='NEEDS_RECONCILIATION' AND t.updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 3 SECOND AND EXISTS(SELECT 1 FROM native_search_run n WHERE n.tenant_id=t.tenant_id AND n.task_id=t.id AND n.worker_id=w.id AND n.phase<>'STOPPED')))) AS overdue FROM worker_process w WHERE host_id=? AND stopped=FALSE ORDER BY id LIMIT 64",
            host)
        .stream()
        .map(
            w ->
                new WorkerProcess(
                    s(w, "id"),
                    n(w, "pid"),
                    n(w, "start_ticks"),
                    n(w, "overdue") == 1,
                    s(w, "boot_id"),
                    s(w, "observer_pid_namespace")))
        .toList();
  }

  private void releaseRun(Map<String, Object> task) {
    int released =
        db.update(
            "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=? AND state='RESERVED'",
            s(task, "tenant_id"),
            s(task, "id") + ":run");
    if (released == 1)
      db.update(
          "UPDATE tenant_budget SET running=running-1 WHERE tenant_id=?", s(task, "tenant_id"));
  }

  private boolean writersQuiescent(Map<String, Object> task) {
    if (!rows(
            "SELECT root_invocation FROM native_search_run WHERE tenant_id=? AND task_id=? AND phase<>'STOPPED' LIMIT 1",
            s(task, "tenant_id"),
            s(task, "id"))
        .isEmpty()) return false;
    // Every attempt matters, including expired attempts fenced out by a later worker.
    // Missing receipts and process-observation failures remain unknown.
    return rows(
            "SELECT a.id FROM task_attempt a LEFT JOIN worker_process w ON w.id=a.worker_id WHERE a.tenant_id=? AND a.task_id=? AND a.quiescent=FALSE AND (w.id IS NULL OR w.stopped=FALSE)",
            s(task, "tenant_id"),
            s(task, "id"))
        .isEmpty();
  }

  @Override
  public void quiescent(Lease lease) {
    tx(
        () -> {
          bucket(lease.tenant());
          var task = locked(lease.tenant(), lease.taskId());
          if (!rows(
                  "SELECT root_invocation FROM native_search_run WHERE tenant_id=? AND attempt_id=? AND phase<>'STOPPED' LIMIT 1",
                  lease.tenant(),
                  lease.attemptId())
              .isEmpty()) return null;
          db.update(
              "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND id=? AND worker_id=? AND fence=?",
              lease.tenant(),
              lease.attemptId(),
              lease.worker(),
              lease.fence());
          settleStoppedWriters(task);
          return null;
        });
  }

  @Override
  public void processStopped(String worker) {
    if (!rows(
            "SELECT root_invocation FROM native_search_run WHERE worker_id=? AND phase<>'STOPPED' LIMIT 1",
            worker)
        .isEmpty()) return;
    for (var candidate :
        rows(
            "SELECT DISTINCT tenant_id,task_id AS id FROM task_attempt WHERE worker_id=?",
            worker)) {
      tx(
          () -> {
            String tenant = s(candidate, "tenant_id");
            bucket(tenant);
            var task = locked(tenant, s(candidate, "id"));
            db.update(
                "UPDATE task_attempt SET quiescent=TRUE WHERE tenant_id=? AND task_id=? AND worker_id=?",
                tenant,
                s(task, "id"),
                worker);
            if (worker.equals(s(task, "worker_id")))
              db.update(
                  "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 MICROSECOND WHERE tenant_id=? AND id=?",
                  tenant,
                  s(task, "id"));
            settleStoppedWriters(task);
            return null;
          });
    }
    // Mark the receipt last: a crash during reconciliation must leave it eligible for retry.
    db.update("UPDATE worker_process SET stopped=TRUE WHERE id=?", worker);
  }

  private void settleStoppedWriters(Map<String, Object> task) {
    if (!writersQuiescent(task)) return;
    if ("CANCEL_REQUESTED".equals(s(task, "state"))) {
      boolean uncertain =
          !rows(
                  "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND state IN ('RUNNING','OUTCOME_UNKNOWN')",
                  s(task, "tenant_id"),
                  s(task, "id"))
              .isEmpty();
      terminal(
          task,
          uncertain ? "NEEDS_RECONCILIATION" : "CANCELLED",
          uncertain ? "EFFECT_OUTCOME_UNKNOWN" : "CANCELLED");
    }
    if (List.of(
            "WAITING_USER",
            "RESUME_READY",
            "CANCEL_REQUESTED",
            "NEEDS_RECONCILIATION",
            "SUCCEEDED",
            "FAILED",
            "CANCELLED")
        .contains(s(task, "state"))) releaseRun(task);
    if (view(locked(s(task, "tenant_id"), s(task, "id"))).terminal()) releaseQuestionTask(task);
    if (JdbcSessionRecords.task(task)
        && !"PURGED".equals(s(sessions.history(task, false), "content_state")))
      sessions.account(task, true);
  }

  @Override
  public void check() {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19') AND success=TRUE",
            Integer.class)
        != 19) throw new IllegalStateException("Required migration unavailable");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE",
            Integer.class)
        != 19) throw new IllegalStateException("Unsupported schema version");
  }

  @Override
  public void cleanupArtifacts(ArtifactStore store) {
    maintainQuestions();
    cleanupQuestions();
    cleanupTodos();
    cleanupSessionHistory();
    cleanupDirectories();
    cleanupMcp();
    cleanupImages();
    var candidates =
        rows(
            "SELECT a.tenant_id,a.id,a.task_id,a.upload_id FROM artifact a JOIN task t ON t.tenant_id=a.tenant_id AND t.id=a.task_id JOIN reservation r ON r.tenant_id=a.tenant_id AND r.id=a.id WHERE r.state<>'RELEASED' AND ((a.state IN ('STAGING','VERIFIED','ABANDONED') AND a.updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 HOUR AND t.state IN ('FAILED','CANCELLED')) OR (a.state IN ('PUBLISHED','EXPIRED') AND a.expires_at<CURRENT_TIMESTAMP(6))) LIMIT 64");
    for (var candidate : candidates) {
      String tenant = s(candidate, "tenant_id"),
          artifact = s(candidate, "id"),
          taskId = s(candidate, "task_id");
      boolean ready =
          tx(
              () -> {
                bucket(tenant);
                var task = locked(tenant, taskId);
                var a =
                    one(
                        "SELECT * FROM artifact WHERE tenant_id=? AND id=? FOR UPDATE",
                        tenant,
                        artifact);
                boolean expired = time(a, "expires_at").isBefore(Instant.now());
                // A RUNNING task may still claim a new attempt after this transaction.
                // Quiescence of existing writers alone cannot make that cleanup safe.
                if (expired
                    && !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state")))
                  return false;
                if (!expired && !List.of("FAILED", "CANCELLED").contains(s(task, "state")))
                  return false;
                if (!writersQuiescent(task)) return false;
                db.update(
                    "UPDATE artifact SET state=? WHERE tenant_id=? AND id=?",
                    expired ? "EXPIRED" : "ABANDONED",
                    tenant,
                    artifact);
                return true;
              });
      if (!ready) continue;
      // Filesystem operations are outside every database transaction. Failed deletion is retried by
      // state.
      store.removeUpload(tenant, s(candidate, "upload_id"));
      tx(
          () -> {
            bucket(tenant);
            locked(tenant, taskId);
            var reservation =
                one("SELECT * FROM reservation WHERE tenant_id=? AND id=?", tenant, artifact);
            if (!"RELEASED".equals(s(reservation, "state"))) {
              db.update(
                  "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                  n(reservation, "amount"),
                  tenant);
              db.update(
                  "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                  tenant,
                  artifact);
            }
            return null;
          });
    }
  }

  private void cleanupImages() {
    if (imageStore == null) return;
    var candidates =
        rows(
            "SELECT tenant_id,id FROM task WHERE record_version=3 AND media_cleanup_state='RETAINED' AND state IN ('SUCCEEDED','FAILED','CANCELLED') AND updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 7 DAY LIMIT 32");
    for (var candidate : candidates) {
      String tenant = s(candidate, "tenant_id"), taskId = s(candidate, "id");
      boolean ready =
          tx(
              () -> {
                bucket(tenant);
                var task = locked(tenant, taskId);
                if (!"RETAINED".equals(s(task, "media_cleanup_state"))
                    || !writersQuiescent(task)
                    || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state")))
                  return false;
                if (!rows(
                        "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND state='HELD_UNKNOWN'",
                        tenant,
                        taskId)
                    .isEmpty()) return false;
                db.update(
                    "UPDATE task_image_object SET cleanup_state='DELETING' WHERE tenant_id=? AND task_id=? AND cleanup_state='RETAINED'",
                    tenant,
                    taskId);
                return true;
              });
      if (!ready) continue;
      var objects =
          rows(
              "SELECT image_id FROM task_image_object WHERE tenant_id=? AND task_id=? AND cleanup_state='DELETING'",
              tenant,
              taskId);
      // Exact host-issued object identities only; no scan of unknown active uploads.
      for (var object : objects) imageStore.removeUpload(tenant, s(object, "image_id"));
      tx(
          () -> {
            bucket(tenant);
            var task = locked(tenant, taskId);
            if ("DELETED".equals(s(task, "media_cleanup_state"))) return null;
            if (!writersQuiescent(task)
                || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(s(task, "state"))
                || !rows(
                        "SELECT id FROM reservation WHERE tenant_id=? AND task_id=? AND state='HELD_UNKNOWN'",
                        tenant,
                        taskId)
                    .isEmpty()) throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
            for (var checkpoint :
                rows(
                    "SELECT id,request_snapshot,request_digest FROM step WHERE tenant_id=? AND task_id=? AND request_cleanup_state='RETAINED' FOR UPDATE",
                    tenant,
                    taskId)) {
              String original = s(checkpoint, "request_snapshot");
              if (!Payloads.hash(original).equals(s(checkpoint, "request_digest")))
                throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
              var retained =
                  Payloads.read(original, com.fasterxml.jackson.databind.node.ObjectNode.class);
              // Only terminal, proven quiescent checkpoints are no longer replayable.
              // Keep the original request/body digests and all scoped metadata as audit facts.
              retained.remove("requestBody");
              String redacted = Payloads.json(retained);
              db.update(
                  "UPDATE step SET request_snapshot=?,request_cleanup_state='MEDIA_PURGED',retained_request_digest=? WHERE tenant_id=? AND id=?",
                  redacted,
                  Payloads.hash(redacted),
                  tenant,
                  s(checkpoint, "id"));
            }
            var held =
                rows(
                    "SELECT * FROM reservation WHERE tenant_id=? AND task_id=? AND category IN ('MEDIA_INPUT','MEDIA_STORAGE','MEDIA_CHECKPOINT') AND state IN ('RESERVED','SETTLED') FOR UPDATE",
                    tenant,
                    taskId);
            for (var reservation : held) {
              long bytes =
                  reservation.get("actual") == null
                      ? n(reservation, "amount")
                      : n(reservation, "actual");
              db.update(
                  "MEDIA_CHECKPOINT".equals(s(reservation, "category"))
                      ? "UPDATE tenant_budget SET media_checkpoint_held=media_checkpoint_held-? WHERE tenant_id=?"
                      : "UPDATE tenant_budget SET storage_held=storage_held-? WHERE tenant_id=?",
                  bytes,
                  tenant);
              db.update(
                  "UPDATE reservation SET state='RELEASED' WHERE tenant_id=? AND id=?",
                  tenant,
                  s(reservation, "id"));
            }
            db.update(
                "UPDATE task_image_input SET raw_base64=NULL WHERE tenant_id=? AND task_id=?",
                tenant,
                taskId);
            db.update(
                "UPDATE task_image_object SET cleanup_state='DELETED' WHERE tenant_id=? AND task_id=? AND cleanup_state='DELETING'",
                tenant,
                taskId);
            db.update(
                "UPDATE task SET media_cleanup_state='DELETED' WHERE tenant_id=? AND id=?",
                tenant,
                taskId);
            return null;
          });
    }
  }
}
