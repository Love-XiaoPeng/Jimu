package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
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
 * InnoDB is the only authority. The owning use case preserves its run/budget/scope/task lock order
 * and rechecks ownership inside the transaction. B1 record operations remain transitional.
 */
public final class JdbcLedger
    implements Tasks,
        ExecutionLedger,
        NativeSearchLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.TodoLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger,
        io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger {
  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionContextService sessionContexts;
  final MapperSessionContexts contextRecords;
  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionTraceService nativeTraces;
  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionSearchService nativeSearches;
  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionReadService nativeReads;
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeQueryService
      scopeQueries;
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeRevocationService
      scopeRevocations;
  private final io.github.lovexiaopeng.jimu.kernel.worker.service.WorkerService workerService;
  private final io.github.lovexiaopeng.jimu.kernel.execution.service.ExecutionService
      executionService;
  private final io.github.lovexiaopeng.jimu.kernel.model.service.ModelDispatchService modelService;
  private final io.github.lovexiaopeng.jimu.kernel.model.service.EvaluationBindingService
      evaluationBindings =
          new io.github.lovexiaopeng.jimu.kernel.model.service.impl.EvaluationBindingServiceImpl(
              new io.github.lovexiaopeng.jimu.adapters.runtime.model.RuntimeEvaluationPlanCodec());
  private final io.github.lovexiaopeng.jimu.kernel.invocation.service.InvocationService
      invocationService;
  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskAdmissionService
      taskAdmissionService;
  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactRetentionService
      artifactRetention;
  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.MediaRetentionService
      mediaRetention;
  private final io.github.lovexiaopeng.jimu.kernel.mcp.service.McpRetentionService mcpRetention;
  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactAccessService
      artifactAccess;
  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskOperationsService
      taskOperations;
  private final MapperTaskOperations taskOperationRecords;
  private final JdbcTemplate db;
  final CoreMappers mappers;
  private final DataSource source;
  private final io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query;
  private final io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
      queryTransactions;
  private final TransactionTemplate transactions;
  private final io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork unitOfWork;
  private final Runnable schemaCheck;
  final ModelProfiles models;
  final JdbcExecutionScopes executionScopes;
  java.util.function.BooleanSupplier searchAvailable = () -> false;
  final JdbcImageRecords images;
  final JdbcMcpRecords mcp;
  private ArtifactStore imageStore;
  final io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts questions =
      new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts();
  boolean questionsEnabled;
  boolean todosEnabled;
  final io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts todos =
      new io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts();

  boolean directoryEnabled;
  boolean sessionReadEnabled;
  boolean sessionSearchEnabled;
  boolean sessionTraceEnabled;

  public void sessionTraceEnabled(boolean enabled) {
    sessionTraceEnabled = enabled;
  }

  public void sessionSearchEnabled(boolean enabled) {
    sessionSearchEnabled = enabled;
  }

  final JdbcSessionRecords sessions;
  final JdbcSessionScopes sessionScopes;
  boolean priorSessionSearchEnabled;

  public void priorSessionSearchEnabled(boolean enabled) {
    priorSessionSearchEnabled = enabled;
  }

  public void sessionReadEnabled(boolean enabled, java.util.List<String> sensitive) {
    sessionReadEnabled = enabled;
    sessions.sensitive(sensitive);
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionEligibilityService
      sessionEligibility =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionEligibilityServiceImpl();

  private void sessionAccess(Map<String, Object> task) {
    sessionEligibility.access(new MapperSessionEligibility(this, task));
  }

  void sessionAdmission(Map<String, Object> task) {
    sessionEligibility.admission(new MapperSessionEligibility(this, task).admission());
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionAvailabilityService
      sessionAvailability =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl
              .SessionAvailabilityServiceImpl();

  private boolean traceAvailable(Identity identity) {
    return sessionAvailability.trace(new MapperSessionAvailability(this, identity));
  }

  private boolean priorAvailable(Identity identity) {
    return sessionAvailability.prior(new MapperSessionAvailability(this, identity));
  }

  private boolean sessionAvailable(Identity identity, boolean search) {
    return sessionAvailability.session(new MapperSessionAvailability(this, identity), search);
  }

  private <T> T sessionTransaction(java.util.function.Supplier<T> work) {
    return sessionTransaction(work, System.nanoTime() + 2_000_000_000L);
  }

  <T> T sessionTransaction(java.util.function.Supplier<T> work, long end) {
    if (query == null) throw new IllegalStateException("Explicit session query context required");
    query.restrictDeadline(end);
    return queryTransactions.required(query, work);
  }

  @Override
  public View submitSessionSearch(
      Identity identity,
      String key,
      io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger.SearchBody body,
      long deadlineNanos) {
    if (query == null)
      return queryView(deadlineNanos, null).submitSessionSearch(identity, key, body, deadlineNanos);
    var input = sessionScopes.contracts.admission(body.read(deadlineNanos));
    return submitSessionSearch(
        identity,
        key,
        input.goal(),
        input.historySessionIds().stream().map(java.util.UUID::fromString).toList(),
        deadlineNanos);
  }

  @Override
  public View submitSessionSearch(
      Identity identity,
      String key,
      String goal,
      List<java.util.UUID> sourceIds,
      long deadlineNanos) {
    if (query == null)
      return queryView(deadlineNanos, null)
          .submitSessionSearch(identity, key, goal, sourceIds, deadlineNanos);
    if (key == null
        || !key.matches("[!-~]{1,128}")
        || goal == null
        || goal.isBlank()
        || io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts.bytes(goal) > 8192
        || sourceIds.size() > 8
        || new java.util.HashSet<>(sourceIds).size() != sourceIds.size())
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    var ids = sourceIds.stream().map(java.util.UUID::toString).sorted().toList();
    return sessionSearchOwnerRead(
        () ->
            submitBound(
                identity,
                key,
                new Request(
                    goal,
                    List.of("session_event_read", "session_search"),
                    List.of(),
                    new Budget(180, 8, 20, null)),
                List.of(),
                ids,
                id()),
        deadlineNanos);
  }

  private Map<String, Object> priorSearchOwner(Identity identity, String caller) {
    try {
      var task = sessionOwner(identity, caller);
      if (!JdbcSessionScopes.task(task)) throw JdbcSessionScopes.unavailable();
      return task;
    } catch (TaskFailure failure) {
      if (failure.status() == 403 || failure.status() == 404) throw JdbcSessionScopes.unavailable();
      throw failure;
    }
  }

  @Override
  public QueryResources authorizePriorSessionSearch(
      Identity identity, String caller, long deadlineNanos) {
    if (query == null)
      return queryView(deadlineNanos, new QueryResources(0, 0, 0))
          .authorizePriorSessionSearch(identity, caller, deadlineNanos);

    sessionSearchOwnerRead(
        () -> {
          priorSearchOwner(identity, caller);
          return null;
        },
        deadlineNanos);
    return query.resources();
  }

  @Override
  public String priorSessionSearch(
      Identity identity,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger.SearchBody body,
      long deadlineNanos,
      QueryResources resources) {
    if (query == null)
      return queryView(deadlineNanos, resources)
          .priorSessionSearch(identity, caller, body, deadlineNanos, resources);

    return sessionSearchOwnerRead(
        () -> {
          var task = priorSearchOwner(identity, caller);
          var input = sessionScopes.contracts.input(body.read(deadlineNanos));
          sessionRemainingMillis();
          var found = sessions.priorSearch(task, input, this::sessionRemainingMillis);
          sessionScopes.require(task);
          sessionRemainingMillis();
          return found.output();
        },
        deadlineNanos);
  }

  @Override
  public void revokeSessionSearchScope(
      Identity identity, String caller, boolean validRequest, long deadlineNanos) {
    if (query == null) {
      queryView(deadlineNanos, null)
          .revokeSessionSearchScope(identity, caller, validRequest, deadlineNanos);
      return;
    }
    scopeRevocations.revoke(identity, caller, validRequest, deadlineNanos);
  }

  @Override
  public String scopedOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    if (query == null)
      return queryView(lease, true).scopedOrResult(lease, stepId, call, version, digest);
    return scopeQueries.scopedOrResult(lease, stepId, call, version, digest);
  }

  private int sessionRemainingMillis() {
    return query.remainingMillis();
  }

  @Override
  public String sessionContext(Lease lease) {
    if (query == null)
      return queryView(System.nanoTime() + 2_000_000_000L, null).sessionContext(lease);
    return sessionContexts.context(lease);
  }

  Map<String, Object> sessionOwner(Identity identity, String taskId) {
    if (!taskId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    // Same lock order as worker commits: bucket -> task -> actor/installation -> history.
    bucket(identity.tenant());
    sessionScopes.lock(identity.tenant(), taskId);
    var found =
        rows(
            "SELECT * FROM task WHERE tenant_id=? AND id=? AND owner_actor_id=? FOR UPDATE",
            identity.tenant(),
            taskId,
            identity.actor());
    if (found.isEmpty()) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    var task = found.getFirst();
    if (!JdbcSessionScopes.task(task)) identity(identity);
    access(task);
    return task;
  }

  <T> T sessionOwnerRead(java.util.function.Supplier<T> work) {
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
    if (query == null)
      return queryView(System.nanoTime() + 2_000_000_000L, null).sessionHistory(identity, taskId);
    return sessions.historyViews.owner(MapperSessionHistoryViews.owner(this, identity, taskId));
  }

  @Override
  public String sessionEvent(Identity identity, String taskId, long seq, int before, int after) {
    if (query == null)
      return queryView(System.nanoTime() + 2_000_000_000L, null)
          .sessionEvent(identity, taskId, seq, before, after);
    return sessionOwnerRead(
        () -> {
          var task = sessionOwner(identity, taskId);
          var input =
              sessions.contracts.input(
                  Payloads.json(Map.of("seq", seq, "before", before, "after", after)));
          long upper = n(sessions.history(task, true), "next_seq") - 1;
          return sessions
              .read(task, input, upper, "owner-snapshot", this::sessionRemainingMillis)
              .output();
        });
  }

  @Override
  public void authorizeSessionSearch(Identity identity, String taskId, long deadlineNanos) {
    if (query == null) {
      queryView(deadlineNanos, null).authorizeSessionSearch(identity, taskId, deadlineNanos);
      return;
    }
    sessionSearchOwnerRead(
        () -> {
          sessionSearchOwner(identity, taskId);
          return null;
        },
        deadlineNanos);
  }

  private Map<String, Object> sessionSearchOwner(Identity identity, String taskId) {
    var task = sessionOwner(identity, taskId);
    if (!JdbcSessionRecords.searchTask(task))
      throw new TaskFailure(409, "SESSION_SEARCH_UNAVAILABLE");
    sessions.history(task, true);
    return task;
  }

  @Override
  public String sessionSearch(
      Identity identity, String taskId, SearchBody body, long deadlineNanos) {
    if (query == null)
      return queryView(deadlineNanos, null).sessionSearch(identity, taskId, body, deadlineNanos);
    return sessionSearchOwnerRead(
        () -> {
          var task = sessionSearchOwner(identity, taskId);
          long upper = n(sessions.history(task, true), "next_seq") - 1;
          byte[] raw = body.read(deadlineNanos);
          sessionRemainingMillis();
          var input = sessions.searchContract(task).input(raw);
          return sessions
              .search(task, input, upper, "owner-snapshot", this::sessionRemainingMillis)
              .output();
        },
        deadlineNanos);
  }

  @Override
  public void authorizeSessionTrace(Identity identity, String taskId, long deadlineNanos) {
    if (query == null) {
      queryView(deadlineNanos, null).authorizeSessionTrace(identity, taskId, deadlineNanos);
      return;
    }
    sessionSearchOwnerRead(
        () -> {
          sessionTraceOwner(identity, taskId);
          return null;
        },
        deadlineNanos);
  }

  private Map<String, Object> sessionTraceOwner(Identity identity, String taskId) {
    var task = sessionOwner(identity, taskId);
    if (!JdbcSessionRecords.traceTask(task))
      throw new TaskFailure(409, "SESSION_TRACE_UNAVAILABLE");
    sessions.history(task, true);
    return task;
  }

  @Override
  public String sessionTrace(
      Identity identity, String taskId, SearchBody body, long deadlineNanos) {
    if (query == null)
      return queryView(deadlineNanos, null).sessionTrace(identity, taskId, body, deadlineNanos);
    return sessionSearchOwnerRead(
        () -> {
          var task = sessionTraceOwner(identity, taskId);
          long upper = n(sessions.history(task, true), "next_seq") - 1;
          var input = sessions.trace.input(body.read(deadlineNanos));
          sessionRemainingMillis();
          return sessions
              .trace(task, input, upper, "owner-snapshot", this::sessionRemainingMillis)
              .output();
        },
        deadlineNanos);
  }

  <T> T sessionSearchOwnerRead(Supplier<T> work, long deadlineNanos) {
    try {
      return sessionTransaction(work, deadlineNanos);
    } catch (org.springframework.dao.QueryTimeoutException
        | org.springframework.dao.CannotAcquireLockException e) {
      throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException e) {
      throw new TaskFailure(
          System.nanoTime() >= deadlineNanos ? 504 : 503,
          System.nanoTime() >= deadlineNanos
              ? "SESSION_QUERY_TIMEOUT"
              : "SESSION_QUERY_UNAVAILABLE");
    }
  }

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionFactService sessionFacts =
      new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionFactServiceImpl();

  long sessionAppend(
      Map<String, Object> task,
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String sourceDigest,
      Lease lease) {
    return sessionFacts.append(
        fact,
        MapperSessionFacts.append(
            this, task, fact, type, source, data, redacted, sourceDigest, lease));
  }

  private void sessionFact(Map<String, Object> task, String fact) {
    sessionFacts.record(fact, new MapperSessionFacts(this, task));
  }

  @Override
  public String readOrResult(Lease lease, String stepId, Call call, String version, String digest) {
    if (query == null)
      return queryView(lease, false).readOrResult(lease, stepId, call, version, digest);
    return nativeReads.readOrResult(lease, stepId, call, version, digest);
  }

  @Override
  public String searchOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    if (query == null)
      return queryView(lease, false).searchOrResult(lease, stepId, call, version, digest);
    return nativeSearches.searchOrResult(lease, stepId, call, version, digest);
  }

  @Override
  public String traceOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    if (query == null)
      return queryView(lease, false).traceOrResult(lease, stepId, call, version, digest);
    return nativeTraces.traceOrResult(lease, stepId, call, version, digest);
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
    return sessionScopes.eligibility.cleanupSafe(MapperSessionScopeRecords.caller(task));
  }

  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeMaintenanceService
      scopeMaintenance;

  private void validateSessionScopes() {
    if (query == null) {
      queryView(System.nanoTime() + 2_000_000_000L, null).validateSessionScopes();
      return;
    }
    scopeMaintenance.validate();
  }

  final io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryRetentionService
      historyRetention;
  final MapperSessionHistoryRetentions historyRetentionRecords;

  private void cleanupSessionHistory() {
    historyRetention.cleanup();
  }

  private io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore directoryWorkspace;
  private final MapperDirectoryRetentions directoryRetentionRecords;
  private final io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryRetentionService
      directoryRetention;

  public void directoryWorkspace(
      io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore workspace) {
    directoryWorkspace = workspace;
  }

  private void cleanupDirectories() {
    directoryRetention.cleanup();
  }

  final io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts directories =
      new io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts();

  public void directoryEnabled(boolean enabled) {
    directoryEnabled = enabled;
  }

  boolean directoryTask(Map<String, Object> task) {
    return caps(task).contains("working_directory");
  }

  Map<String, Object> directoryProjection(Map<String, Object> task) {
    var p = directoryRetentionRecords.projectionRow(s(task, "tenant_id"), s(task, "id"));
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

  io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts.Contract
      directoryFileContract(String name) {
    try {
      return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts()
          .load(name);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  void directoryAdmission(Map<String, Object> task) {
    if (!directoryEnabled) throw new TaskFailure(403, "DIRECTORY_FEATURE_DISABLED");
    if (!"PRESENT".equals(s(directoryProjection(task), "content_state")))
      throw new TaskFailure(410, "DIRECTORY_CONTENT_EXPIRED");
    models.requireDirectory(snapshot(task));
  }

  void directoryStorage(Map<String, Object> task, long additional) {
    if (!directoryTask(task)) return;
    io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryStorageLimit.check(
        directoryRetentionRecords.storageBytes(s(task, "tenant_id"), s(task, "id")), additional);
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

  Map<String, Object> originalDirectorySlot(
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

  Map<String, Object> freezeDirectorySlot(Map<String, Object> task, Map<String, Object> i) {
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
                mappers
                    .events()
                    .selectFactSequenceValue(
                        query, lease.tenant(), lease.taskId(), "directory-change:" + invocationId);
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

  void directoryContext(Map<String, Object> task, String request) {
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

  boolean todoTask(Map<String, Object> task) {
    return caps(task).contains("todo_write");
  }

  Map<String, Object> todoProjection(Map<String, Object> task) {
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

  void todoStorage(Map<String, Object> task, long additional) {
    if (!todoTask(task)) return;
    long bytes =
        db.queryForObject(
            "SELECT COALESCE(OCTET_LENGTH(t.request_snapshot),0)+COALESCE(OCTET_LENGTH(t.goal),0)+COALESCE(OCTET_LENGTH(t.summary),0)+COALESCE(OCTET_LENGTH(t.model_snapshot),0)+COALESCE(OCTET_LENGTH(t.tools_snapshot),0)+(SELECT OCTET_LENGTH(binding_snapshot)+COALESCE(OCTET_LENGTH(retained_digests),0) FROM task_todo_projection WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(COALESCE(OCTET_LENGTH(usage_snapshot),0)),0) FROM model_dispatch WHERE tenant_id=t.tenant_id AND task_id=t.id) FROM task t WHERE tenant_id=? AND id=?",
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"));
    if (bytes + additional + 65536 > 2097152) throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
  }

  void todoCommitFence(Lease lease) {
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
                            mappers
                                .events()
                                .selectFactSequenceValue(
                                    query,
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

  private void releaseQuestionTask(Map<String, Object> task) {
    taskOperationRecords.row(task).releaseQuestionTask();
  }

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoRetentionService todoRetention;

  private void cleanupTodos() {
    todoRetention.cleanup();
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionRetentionService
      questionRetention;

  private void cleanupQuestions() {
    questionRetention.cleanup();
  }

  boolean questionTask(Map<String, Object> task) {
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

  void questionPause(Map<String, Object> task, Lease lease, String state, String continuation) {
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
    taskOperations.closeQuestions(taskOperationRecords.row(task), code);
  }

  @Override
  public List<String> pendingQuestionMessages(Lease lease) {
    return leaseTx(lease, () -> questionMessages(fenced(lease, true)).values().stream().toList());
  }

  Map<String, String> questionMessages(Map<String, Object> task) {
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

  void questionStorage(Map<String, Object> task, long additional) {
    if (!questionTask(task)) return;
    long used =
        db.queryForObject(
            "SELECT COALESCE(OCTET_LENGTH(request_snapshot),0)+COALESCE(OCTET_LENGTH(goal),0)+COALESCE(OCTET_LENGTH(summary),0)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(response_snapshot),0)),0) FROM step WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(input_snapshot)+COALESCE(OCTET_LENGTH(output_snapshot),0)),0) FROM invocation WHERE tenant_id=t.tenant_id AND task_id=t.id)+(SELECT COALESCE(SUM(OCTET_LENGTH(request_snapshot)+COALESCE(OCTET_LENGTH(answer_snapshot),0)+COALESCE(OCTET_LENGTH(receipt_snapshot),0)),0) FROM user_question WHERE tenant_id=t.tenant_id AND task_id=t.id) FROM task t WHERE tenant_id=? AND id=?",
            Long.class,
            s(task, "tenant_id"),
            s(task, "id"));
    if (used + additional > 2097152) throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
  }

  void maintainQuestions() {
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
    mcpRetention.cleanup();
  }

  void accountSession(Map<String, Object> task) {
    sessions.account(task, false);
  }

  boolean imagesAvailable() {
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
    this(SessionQueryDeadline.source(source), models, null, null, null);
  }

  /** B1 过渡装配；产品传入已包装的唯一 DS，旧夹具构造在 B5 删除。 */
  public JdbcLedger(
      DataSource source,
      ModelProfiles models,
      DataSourceTransactionManager manager,
      io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork unitOfWork,
      Runnable schemaCheck) {
    this(
        source,
        models,
        manager,
        unitOfWork,
        schemaCheck,
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            source));
  }

  public JdbcLedger(
      DataSource source,
      ModelProfiles models,
      DataSourceTransactionManager manager,
      io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork unitOfWork,
      Runnable schemaCheck,
      org.mybatis.spring.SqlSessionTemplate sessions) {
    this(source, models, manager, unitOfWork, schemaCheck, CoreMappers.from(sessions), null);
  }

  private JdbcLedger(
      DataSource source,
      ModelProfiles models,
      DataSourceTransactionManager manager,
      io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork unitOfWork,
      Runnable schemaCheck,
      CoreMappers mappers,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    this.source = source;
    this.mappers = mappers;
    this.query = query;
    this.models = models;
    db =
        query == null
            ? new JdbcTemplate(source)
            : new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionJdbcTemplate(
                source, query);
    sessions = new JdbcSessionRecords(db, query, mappers.session());
    contextRecords = new MapperSessionContexts(this);
    sessionContexts =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionContextServiceImpl(
            contextRecords);
    sessions.context = task -> sessionContexts.snapshot(contextRecords.bind(task));
    sessionScopes = new JdbcSessionScopes(this, db, sessions);
    scopeQueries =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionScopeQueryServiceImpl(
            new MapperSessionScopeQueries(this));
    scopeRevocations =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl
            .SessionScopeRevocationServiceImpl(new MapperSessionScopeRevocations(this));
    nativeReads =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionReadServiceImpl(
            new MapperSessionReads(this));
    nativeSearches =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionSearchServiceImpl(
            new MapperSessionSearches(this));
    nativeTraces =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionTraceServiceImpl(
            new MapperSessionTraces(this));
    sessions.scopes = sessionScopes;
    sessionScopes.invalidated = this::invalidateScopeAfterTransaction;
    images = new JdbcImageRecords(db);
    mcp = new JdbcMcpRecords(db);
    db.setQueryTimeout(5);
    executionScopes = new JdbcExecutionScopes(db, models);
    var shared = manager == null ? new DataSourceTransactionManager(source) : manager;
    queryTransactions =
        new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction(
            source, shared);
    transactions = new TransactionTemplate(shared);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
    this.unitOfWork =
        unitOfWork == null
            ? new io.github.lovexiaopeng.jimu.adapters.persistence.transaction
                .SpringLedgerUnitOfWork(shared)
            : unitOfWork;
    this.schemaCheck = schemaCheck;
    var records = new JdbcCoreRecords(this, db);
    var coreWork =
        new io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork() {
          @Override
          public <T> T required(Supplier<T> work) {
            return tx(work);
          }
        };
    var leaseWork =
        new io.github.lovexiaopeng.jimu.kernel.task.api.LeaseUnitOfWork() {
          public <T> T admission(Lease lease, Supplier<T> work) {
            return leaseTx(lease, work);
          }

          public <T> T directory(Lease lease, Supplier<T> work) {
            return directoryTransaction(lease, work);
          }
        };
    taskOperationRecords = new MapperTaskOperations(this);
    scopeMaintenance =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl
            .SessionScopeMaintenanceServiceImpl(new MapperSessionScopeMaintenanceRecords(this));
    historyRetentionRecords = new MapperSessionHistoryRetentions(this);
    historyRetention =
        new io.github.lovexiaopeng.jimu.kernel.session.service.impl
            .SessionHistoryRetentionServiceImpl(historyRetentionRecords, coreWork, Payloads::hash);
    todoRetention =
        new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoRetentionServiceImpl(
            new MapperTodoRetentions(this), coreWork, Payloads::hash);
    questionRetention =
        new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionRetentionServiceImpl(
            new MapperQuestionRetentions(this), coreWork, Payloads::hash);
    directoryRetentionRecords = new MapperDirectoryRetentions(this);
    directoryRetention =
        new io.github.lovexiaopeng.jimu.kernel.directory.service.impl.DirectoryRetentionServiceImpl(
            directoryRetentionRecords,
            new WorkspaceDirectoryRetentions(() -> directoryWorkspace),
            coreWork,
            Payloads::hash);
    mcpRetention =
        new io.github.lovexiaopeng.jimu.kernel.mcp.service.impl.McpRetentionServiceImpl(
            new MapperMcpRetentions(this), coreWork);
    taskOperations =
        new io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskOperationsServiceImpl(
            taskOperationRecords, coreWork);
    artifactRetention =
        new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactRetentionServiceImpl(
            new MapperArtifactRetentions(this), coreWork, java.time.Clock.systemUTC());
    mediaRetention =
        new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.MediaRetentionServiceImpl(
            new MapperMediaRetentions(this),
            coreWork,
            Payloads::hash,
            (snapshot, field) -> {
              var retained =
                  Payloads.read(snapshot, com.fasterxml.jackson.databind.node.ObjectNode.class);
              retained.remove(field);
              return Payloads.json(retained);
            });
    artifactAccess =
        new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactAccessServiceImpl(
            new MapperArtifactDownloads(this), coreWork, java.time.Clock.systemUTC());
    taskAdmissionService =
        new io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskAdmissionServiceImpl(
            new MapperTaskAdmissions(this), coreWork);
    workerService =
        new io.github.lovexiaopeng.jimu.kernel.worker.service.impl.WorkerServiceImpl(
            new MapperWorkerRecords(this), coreWork);
    executionService =
        new io.github.lovexiaopeng.jimu.kernel.execution.service.impl.ExecutionServiceImpl(
            records.execution(), coreWork, leaseWork, Payloads::hash);
    modelService =
        new io.github.lovexiaopeng.jimu.kernel.model.service.impl.ModelDispatchServiceImpl(
            new MapperModelDispatchRecords(this),
            coreWork,
            new MapperModelWorkflows(this),
            leaseWork,
            Payloads::hash);
    invocationService =
        new io.github.lovexiaopeng.jimu.kernel.invocation.service.impl.InvocationServiceImpl(
            new MapperInvocationRecords(this),
            coreWork,
            new MapperInvocationWorkflows(this),
            leaseWork,
            Payloads::hash);
  }

  private JdbcLedger queryView(long end, QueryResources resources) {
    var scoped =
        new JdbcLedger(
            source,
            models,
            (DataSourceTransactionManager) transactions.getTransactionManager(),
            unitOfWork,
            schemaCheck,
            mappers,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(end, resources));
    scoped.searchAvailable = searchAvailable;
    scoped.imageStore = imageStore;
    scoped.questionsEnabled = questionsEnabled;
    scoped.todosEnabled = todosEnabled;
    scoped.directoryEnabled = directoryEnabled;
    scoped.directoryWorkspace = directoryWorkspace;
    scoped.sessionReadEnabled = sessionReadEnabled;
    scoped.sessionSearchEnabled = sessionSearchEnabled;
    scoped.sessionTraceEnabled = sessionTraceEnabled;
    scoped.priorSessionSearchEnabled = priorSessionSearchEnabled;
    scoped.sessions.copyConfiguration(sessions);
    scoped.mcp.copyConfiguration(mcp);
    return scoped;
  }

  private JdbcLedger queryView(Lease lease, boolean metered) {
    long now = System.nanoTime();
    return queryView(
        Math.min(
            now + 2_000_000_000L,
            now + java.time.Duration.between(Instant.now(), lease.deadline()).toNanos()),
        metered ? new QueryResources(0, 0, 0) : null);
  }

  private <T> T tx(Supplier<T> work) {
    AdmissionResult<T> result =
        unitOfWork.required(
            () -> {
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
          var task = fenced(lease, true);
          // fenced holds evaluation -> budget -> task; only re-read our already locked budget.
          var budget = bucket(lease.tenant());
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

  String requireScope(
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
  <T> T leaseTx(Lease lease, Supplier<T> work) {
    AdmissionResult<T> result =
        tx(
            () -> {
              try {
                return new AdmissionResult<T>(directoryTransaction(lease, work), null);
              } catch (TaskFailure rejection) {
                if (!rejection.code().equals("EVALUATION_MATERIAL_MISMATCH")) throw rejection;
                mappers.evaluations().stopTaskRun(query, lease.tenant(), lease.taskId());
                return new AdmissionResult<T>(null, rejection);
              }
            });
    if (result.rejection() != null) throw result.rejection();
    return result.value();
  }

  <T> T directoryTransaction(Lease lease, Supplier<T> work) {
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

  static Map<String, Object> first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return rows.getFirst();
  }

  io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext queryContext() {
    return query;
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  Map<String, Object> one(String sql, Object... args) {
    var result = rows(sql, args);
    if (result.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return result.getFirst();
  }

  static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  static Instant time(Map<String, Object> row, String key) {
    return row.get(key) == null
        ? null
        : ((java.time.LocalDateTime) row.get(key)).toInstant(java.time.ZoneOffset.UTC);
  }

  static String id() {
    return UUID.randomUUID().toString();
  }

  Map<String, Object> bucket(String tenant) {
    return first(mappers.budgets().selectBudgetForUpdate(query, tenant));
  }

  void identity(Identity identity) {
    var result =
        mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper
                    .class)
            .lockActor(query, identity.tenant(), identity.actor());
    if (result.isEmpty() || !Boolean.TRUE.equals(result.getFirst().get("enabled")))
      throw new TaskFailure(403, "ACCESS_REVOKED");
  }

  long epoch(Identity identity, List<String> capabilities) {
    identity(identity);
    long epoch = 0;
    for (String capability : capabilities.stream().sorted().toList()) {
      var installed =
          mappers
              .session()
              .getMapper(
                  io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper
                      .class)
              .lockInstallation(query, identity.tenant(), capability);
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
              .filter(
                  name -> !name.equals("session_event_read") || sessionAvailable(identity, false))
              .filter(
                  name -> !name.equals("session_event_search") || sessionAvailable(identity, true))
              .filter(name -> !name.equals("session_event_trace") || traceAvailable(identity))
              .filter(name -> !name.equals("session_search") || priorAvailable(identity))
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
    return submitBound(identity, key, original, input, null, null);
  }

  private View submitBound(
      Identity identity,
      String key,
      Request original,
      List<io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.Input> input,
      List<String> historySources,
      String callerId) {
    return taskAdmissionService.submit(identity, key, original, input, historySources, callerId);
  }

  Map<String, Object> locked(String tenant, String task) {
    sessionScopes.lock(tenant, task);
    return first(mappers.tasks().selectTaskForUpdate(query, tenant, task));
  }

  Map<String, Object> visible(Identity identity, String task) {
    sessionScopes.lock(identity.tenant(), task);
    try {
      identity(identity);
    } catch (TaskFailure failure) {
      var found = new MapperTaskVisibility(mappers.session()).select(query, identity, task, true);
      if (found.size() == 1 && JdbcSessionScopes.task(found.getFirst()))
        invalidateScopeAfterTransaction(found.getFirst());
      throw failure;
    }
    return first(new MapperTaskVisibility(mappers.session()).select(query, identity, task, false));
  }

  List<String> caps(Map<String, Object> row) {
    return Arrays.asList(s(row, "capabilities").split(","));
  }

  private final class ScopeInvalidation
      implements org.springframework.transaction.support.TransactionSynchronization {
    private final String tenant, caller;

    ScopeInvalidation(String tenant, String caller) {
      this.tenant = tenant;
      this.caller = caller;
    }

    @Override
    public void afterCompletion(int status) {
      // The failed admission is already rolled back (or genuine usage settled). Persist only the
      // observed authority loss, never any rejected result, under the original absolute deadline.
      Long original = query == null ? null : query.deadlineNanos();
      long end = original == null ? System.nanoTime() + 2_000_000_000L : original;
      if (System.nanoTime() >= end) return;
      var scoped = query == null ? queryView(end, null) : JdbcLedger.this;
      scoped.persistScopeInvalidation(tenant, caller);
    }
  }

  private void persistScopeInvalidation(String tenant, String caller) {
    scopeMaintenance.persistInvalidation(tenant, caller);
  }

  <T> T scopeRequiresNew(java.util.function.Supplier<T> work) {
    return queryTransactions.requiresNew(query, work);
  }

  void invalidateScopeAfterTransaction(Map<String, Object> task) {
    String tenant = s(task, "tenant_id"), caller = s(task, "id");
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive())
      throw new IllegalStateException("Scope authority requires transaction");
    boolean registered =
        org
            .springframework
            .transaction
            .support
            .TransactionSynchronizationManager
            .getSynchronizations()
            .stream()
            .anyMatch(
                sync ->
                    sync instanceof ScopeInvalidation invalid
                        && invalid.tenant.equals(tenant)
                        && invalid.caller.equals(caller));
    if (!registered)
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(new ScopeInvalidation(tenant, caller));
  }

  void access(Map<String, Object> row) {
    try {
      baseAccess(row);
      if (JdbcSessionScopes.task(row)) sessionScopes.require(row);
    } catch (TaskFailure failure) {
      if (JdbcSessionScopes.task(row) && failure.status() == 403) {
        invalidateScopeAfterTransaction(row);
        throw JdbcSessionScopes.unavailable();
      }
      throw failure;
    }
  }

  private void baseAccess(Map<String, Object> row) {
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
    return taskOperations.get(identity, task);
  }

  View view(Map<String, Object> row) {
    return taskOperations.view(taskOperationRecords.row(row));
  }

  @Override
  public View cancel(Identity identity, String task) {
    return taskOperations.cancel(identity, task);
  }

  @Override
  public List<Event> events(Identity identity, String task, long after, int limit) {
    return taskOperations.events(identity, task, after, limit);
  }

  @Override
  public Download download(Identity identity, String artifactId) {
    return artifactAccess.authorize(identity, artifactId);
  }

  public io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactAccessService
      artifactAccess() {
    return artifactAccess;
  }

  long storageHeld(Map<String, Object> budget) {
    return n(budget, "storage_held") + n(budget, "media_checkpoint_held");
  }

  void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String quality) {
    mappers
        .reservations()
        .insertReservation(
            query, tenant, reservation, task, dispatch, category, amount, state, quality);
  }

  void event(Map<String, Object> task, String fact, String type, String code) {
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    if (!mappers.events().selectFactSequence(query, tenant, taskId, fact).isEmpty()) return;
    if (todoTask(task) || directoryTask(task) || JdbcSessionRecords.task(task)) {
      long seq = mappers.tasks().selectNextEventSequence(query, tenant, taskId);
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
    mappers.tasks().advanceEventSequence(query, tenant, taskId);
    long seq = mappers.tasks().selectNextEventSequence(query, tenant, taskId);
    mappers
        .events()
        .insertFact(
            query,
            tenant,
            taskId,
            seq,
            taskId + ":" + seq,
            fact,
            type,
            code,
            mappers.tasks().selectState(query, tenant, taskId),
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
    return workerService.claim(worker);
  }

  Optional<io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords.Candidate> claimCandidate(
      String tenant) {
    var ready = mappers.tasks().selectReadyCandidate(query, tenant);

    if (ready.isEmpty()) return Optional.empty();
    var row = ready.getFirst();
    return Optional.of(
        new io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords.Candidate(
            tenant, s(row, "id"), s(row, "evaluation_run_id")));
  }

  Optional<io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords.Claim> lockClaim(
      io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords.Candidate key) {
    String tenant = key.tenant();
    var candidate = new java.util.HashMap<String, Object>();
    candidate.put("id", key.taskId());
    candidate.put("evaluation_run_id", key.evaluationRun());
    var evaluation =
        evaluation(tenant, executionScopes.targetRun(tenant, s(candidate, "evaluation_run_id")));
    var budget = bucket(tenant);
    sessionScopes.lock(tenant, s(candidate, "id"));
    var candidates = mappers.tasks().claimExactCandidate(query, tenant, s(candidate, "id"));
    if (candidates.isEmpty()) return Optional.empty();
    var task = candidates.getFirst();

    String taskId = s(task, "id");
    return Optional.of(
        new io.github.lovexiaopeng.jimu.kernel.worker.port.WorkerRecords.Claim() {
          @Override
          public boolean runUnchanged() {
            return java.util.Objects.equals(
                candidate.get("evaluation_run_id"), task.get("evaluation_run_id"));
          }

          @Override
          public String state() {
            return s(task, "state");
          }

          @Override
          public boolean requiresStoppedWriters() {
            return directoryTask(task) || JdbcSessionRecords.task(task);
          }

          @Override
          public boolean writersQuiescent() {
            return JdbcLedger.this.writersQuiescent(task);
          }

          @Override
          public boolean runReserved() {
            return !mappers
                .reservations()
                .selectReservedSlot(query, tenant, taskId + ":run")
                .isEmpty();
          }

          @Override
          public boolean runningLimitReached() {
            return n(budget, "running") >= n(budget, "running_limit");
          }

          @Override
          public boolean pendingEffect() {
            return !mappers.invocations().selectPendingEffects(query, tenant, taskId).isEmpty();
          }

          @Override
          public String modelRejection() {
            return JdbcLedger.this.modelRejection(task);
          }

          @Override
          public String runRejection() {
            return JdbcLedger.this.runRejection(evaluation);
          }

          @Override
          public void requireScope() {
            JdbcLedger.this.requireScope(tenant, evaluation, task, null);
          }

          @Override
          public void markRunningInvocationsUnknown() {
            mappers.invocations().markRunningEffectsUnknown(query, tenant, taskId);
          }

          @Override
          public void terminal(String state, String code) {
            JdbcLedger.this.terminal(task, state, code);
          }

          @Override
          public void expireCurrentAttempt() {
            mappers.attempts().expireActiveAttempt(query, tenant, s(task, "current_attempt_id"));
          }

          @Override
          public boolean withinDeadline() {
            return n(task, "within_deadline") == 1;
          }

          @Override
          public void checkAdmission(boolean resume) {
            access(task);
            if (JdbcSessionRecords.task(task)) {
              sessionAdmission(task);
              if (mappers.attempts().countTaskAttempts(query, tenant, taskId) >= 8)
                throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
            }
            if (directoryTask(task)) {
              directoryAdmission(task);
              if (mappers.attempts().countTaskAttempts(query, tenant, taskId) >= 8)
                throw new TaskFailure(429, "DIRECTORY_EXECUTION_LIMIT");
            }
            if (todoTask(task)) {
              if (!todosEnabled) throw new TaskFailure(403, "TODO_FEATURE_DISABLED");
              models.requireTodo(snapshot(task));
              if (n(todoProjection(task), "revision") < 0)
                throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
              if (mappers.attempts().countTaskAttempts(query, tenant, taskId) >= 8)
                throw new TaskFailure(429, "TODO_EXECUTION_LIMIT");
            }
            if (questionTask(task) && !questionsEnabled)
              throw new TaskFailure(403, "QUESTION_FEATURE_DISABLED");
            if (resume) {
              snapshot(task);
              questionStorage(task, 131072);
              todoStorage(task, 131072);
            }
          }

          @Override
          public boolean cancellationRequested() {
            return task.get("cancel_requested_at") != null;
          }

          @Override
          public long fence() {
            return n(task, "fence");
          }

          @Override
          public void moveQueueToRunning() {
            mappers.budgets().moveQueuedToRunning(query, tenant);
            mappers.reservations().releaseQueueSlot(query, tenant, taskId + ":queue");
            reserve(tenant, taskId, taskId + ":run", null, "RUNNING", 1, "RESERVED", "KNOWN");
          }

          @Override
          public void resumeRunning() {
            if (mappers.reservations().reserveReleasedRun(query, tenant, taskId + ":run") != 1)
              throw new TaskFailure(409, "RESERVATION_STATE");
            mappers.budgets().incrementRunning(query, tenant);
          }

          @Override
          public void insertAttempt(String worker, String attempt, long fence) {
            mappers.attempts().insertActiveAttempt(query, tenant, attempt, taskId, worker, fence);
          }

          @Override
          public void assignLease(String worker, String attempt, long fence) {
            mappers.tasks().assignClaimedLease(query, fence, attempt, worker, tenant, taskId);
          }

          @Override
          public void startedEvent(long fence) {
            event(task, "attempt:" + fence, "task.started", null);
          }

          @Override
          public Lease lease(String worker, String attempt, long fence) {
            return new Lease(
                tenant,
                s(task, "owner_actor_id"),
                taskId,
                attempt,
                fence,
                worker,
                time(task, "deadline"),
                s(task, "goal"),
                caps(task),
                n(task, "installation_epoch"));
          }
        });
  }

  Map<String, Object> fenced(Lease lease, boolean admission) {
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
    if (mappers
            .tasks()
            .commitLiveFence(
                query,
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

  EvaluationMaterials.Material material(Map<String, Object> task) {
    if (task.get("evaluation_snapshot") == null) return null;
    String json = s(task, "material_snapshot");
    EvaluationMaterials.require(
        json != null && Payloads.hash(json).equals(s(task, "material_digest")));
    var stored = Payloads.read(json, EvaluationMaterials.Material.class);
    var binding = Payloads.read(s(task, "evaluation_snapshot"), EvaluationPlan.Binding.class);
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

  void checkMaterials(Map<String, Object> task) {
    var material = material(task);
    if (material == null) return;
    for (var invocation :
        mappers.invocations().selectMaterialSources(query, s(task, "tenant_id"), s(task, "id"))) {
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
          mappers
              .invocations()
              .selectMaterialFileHistory(query, s(task, "tenant_id"), s(task, "id"))) {
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

  ReportFile initialFile(Map<String, Object> task) {
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

  void materialBody(Map<String, Object> task, String request, int ordinal) {
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

  String runRejection(Map<String, Object> evaluation) {
    return evaluation != null && !"OPEN".equals(s(evaluation, "state"))
        ? "MODEL_BUDGET_EXCEEDED"
        : null;
  }

  // The dispatch fact is the execution decision, including rows written before this fix.
  // Fixture failures may retry; a paid failed dispatch must retain its original rejection.
  String modelRejection(Map<String, Object> task) {
    var rejected =
        mappers
            .dispatches()
            .selectTaskRejection(queryContext(), s(task, "tenant_id"), s(task, "id"));
    return rejected.isEmpty() ? null : s(rejected.getFirst(), "code");
  }

  @Override
  public boolean renew(Lease lease) {
    return workerService.renew(lease);
  }

  @Override
  public Optional<Step> checkpoint(Lease lease) {
    return executionService.checkpoint(lease);
  }

  ModelSnapshot snapshot(Map<String, Object> task) {
    var snapshot = historicalSnapshot(task);
    models.available(snapshot);
    return snapshot;
  }

  ModelSnapshot historicalSnapshot(Map<String, Object> task) {
    String value = s(task, "model_snapshot");
    if (value == null) throw new TaskFailure(409, "MODEL_SNAPSHOT_MISSING");
    if (!Payloads.hash(value).equals(s(task, "model_digest")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var snapshot = Payloads.read(value, ModelSnapshot.class);
    return snapshot;
  }

  @Override
  public ModelSnapshot modelSnapshot(Lease lease) {
    return modelService.snapshot(lease);
  }

  ToolSnapshot tools(Map<String, Object> task) {
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
    return modelService.tools(lease);
  }

  // This is always the first lock when a transaction can mutate evaluation accounting.
  Map<String, Object> evaluation(Lease lease) {
    return evaluation(lease, true);
  }

  Map<String, Object> evaluation(Lease lease, boolean admission) {
    String run =
        s(
            first(mappers.tasks().selectEvaluationRun(query, lease.tenant(), lease.taskId())),
            "evaluation_run_id");
    return evaluation(
        lease.tenant(), admission ? executionScopes.targetRun(lease.tenant(), run) : run);
  }

  Map<String, Object> evaluation(String tenant, String run) {
    if (run == null) return null;
    var found = mappers.evaluations().selectRunForUpdate(query, tenant, run);
    if (found.isEmpty()) throw new TaskFailure(503, "MODEL_EVALUATION_UNAVAILABLE");
    return found.getFirst();
  }

  EvaluationPlan.Plan plan(Map<String, Object> evaluation) {
    return evaluationBindings.plan(new MapperEvaluationBindings(this, evaluation));
  }

  EvaluationPlan.Binding bindEvaluation(Map<String, Object> evaluation, ModelSnapshot model) {
    return evaluationBindings.bind(new MapperEvaluationBindings(this, evaluation), model);
  }

  void checkEvaluation(
      Map<String, Object> task, Map<String, Object> evaluation, ModelSnapshot model) {
    if (businessTask(task)) {
      requireBusiness(s(task, "tenant_id"), evaluation, task, model);
      return;
    }
    evaluationBindings.check(
        new MapperEvaluationBindings(this, evaluation),
        new io.github.lovexiaopeng.jimu.kernel.model.service.EvaluationBindingService.StoredBinding(
            s(task, "evaluation_snapshot"),
            s(task, "evaluation_digest"),
            s(task, "evaluation_phase"),
            s(task, "evaluation_sample"),
            s(task, "evaluation_model")),
        model);
  }

  @Override
  public Step prepare(Lease lease, String request) {
    return executionService.prepare(lease, request);
  }

  @Override
  public String dispatchModel(Lease lease, String stepId) {
    return modelService.dispatch(lease, stepId);
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
    modelService.responded(lease, stepId, dispatch, response, calls, tokens, usage);
  }

  @Override
  public void modelFailed(Lease lease, String dispatch, String code, ModelUsage usage) {
    modelService.failed(lease, dispatch, code, usage);
  }

  @Override
  public Invocation admit(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest) {
    return invocationService.admit(lease, stepId, call, pluginVersion, pluginDigest);
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
    if (!mappers.events().selectFactSequence(query, lease.tenant(), lease.taskId(), fact).isEmpty())
      return;
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
    invocationService.succeeded(lease, invocationId, output);
  }

  private void commitTool(Lease lease, String invocationId, String output, boolean mcpCommit) {
    invocationService.commit(lease, invocationId, output, mcpCommit);
  }

  @Override
  public void uncertain(Lease lease, String invocationId) {
    invocationService.uncertain(lease, invocationId);
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

  boolean publicReport(Map<String, Object> task) {
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
    invocationService.failed(lease, invocationId, code);
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
    if (executionService.finish(lease, summary))
      throw new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionSuspended();
  }

  @Override
  public void stop(Lease lease, String code) {
    executionService.stop(lease, code);
  }

  void terminal(Map<String, Object> task, String state, String code) {
    taskOperations.terminal(taskOperationRecords.row(task), state, code);
  }

  @Override
  public void registerWorker(
      String worker,
      String host,
      long pid,
      long startTicks,
      String bootId,
      String observerPidNamespace) {
    workerService.register(worker, host, pid, startTicks, bootId, observerPidNamespace);
  }

  @Override
  public List<WorkerProcess> processes(String host) {
    return workerService.processes(host);
  }

  private void releaseRun(Map<String, Object> task) {
    taskOperationRecords.row(task).releaseRun();
  }

  boolean writersQuiescent(Map<String, Object> task) {
    if (!mappers
        .attempts()
        .selectActiveNativeTask(query, s(task, "tenant_id"), s(task, "id"))
        .isEmpty()) return false;
    // Every attempt matters, including expired attempts fenced out by a later worker.
    // Missing receipts and process-observation failures remain unknown.
    return mappers
        .attempts()
        .selectUnobservedWriters(query, s(task, "tenant_id"), s(task, "id"))
        .isEmpty();
  }

  @Override
  public void quiescent(Lease lease) {
    workerService.quiescent(lease);
  }

  @Override
  public void processStopped(String worker) {
    workerService.processStopped(worker);
  }

  void settleStoppedWriters(Map<String, Object> task) {
    if (!writersQuiescent(task)) return;
    if ("CANCEL_REQUESTED".equals(s(task, "state"))) {
      boolean uncertain =
          !mappers
              .invocations()
              .selectPendingEffects(query, s(task, "tenant_id"), s(task, "id"))
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
    if (schemaCheck != null) {
      schemaCheck.run();
      return;
    }
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22') AND success=TRUE",
            Integer.class)
        != 22) throw new IllegalStateException("Required migration unavailable");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE",
            Integer.class)
        != 22) throw new IllegalStateException("Unsupported schema version");
  }

  @Override
  public void cleanupArtifacts(ArtifactStore store) {
    maintainQuestions();
    cleanupQuestions();
    cleanupTodos();
    validateSessionScopes();
    cleanupSessionHistory();
    cleanupDirectories();
    cleanupMcp();
    cleanupImages();
    artifactRetention.cleanup(store);
  }

  private void cleanupImages() {
    mediaRetention.cleanup(imageStore);
  }
}
