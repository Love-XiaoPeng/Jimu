package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
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
  private final LedgerPersistenceDependencies persistence;
  private final DataSource source;
  private final io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query;
  private final io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
      queryTransactions;
  private final TransactionTemplate transactions;
  private final io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork unitOfWork;
  private final Runnable schemaCheck;
  final ModelProfiles models;
  final io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeConsumptionService
      scopeConsumption;
  java.util.function.BooleanSupplier searchAvailable = () -> false;
  final MapperImageRecords imageRecords;
  final io.github.lovexiaopeng.jimu.kernel.media.service.ImagePersistenceService imageService;
  final MapperMcpRecords mcpRecords;
  final io.github.lovexiaopeng.jimu.kernel.mcp.service.McpPersistenceService mcpService;
  private final io.github.lovexiaopeng.jimu.kernel.search.service.NativeSearchPersistenceService
      nativeSearchService;
  private final io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryPersistenceService
      directoryService;
  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoPersistenceService todoService;
  final io.github.lovexiaopeng.jimu.kernel.model.service.MaterialEvidenceService materialEvidence;

  byte[] materialImageBytes(String tenant, String key, String hash, long size) {
    return imageStore.read(tenant, key, hash, size);
  }

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

  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionOwnerService
      sessionOwners =
          new io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionOwnerServiceImpl();
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
    return sessionOwners.owner(identity, taskId, new MapperSessionOwners(this));
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
    return MapperDirectoryRecords.unwrap(
        directoryService.directoryProjection(MapperDirectoryRecords.wrap(task)));
  }

  private final io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryEligibilityService
      directoryEligibility =
          new io.github.lovexiaopeng.jimu.kernel.directory.service.impl
              .DirectoryEligibilityServiceImpl();

  private void directoryAccess(Map<String, Object> task) {
    directoryEligibility.access(new MapperDirectoryEligibility(this, task));
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

  private final io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryAdmissionService
      directoryAdmissions =
          new io.github.lovexiaopeng.jimu.kernel.directory.service.impl
              .DirectoryAdmissionServiceImpl();

  void directoryAdmission(Map<String, Object> task) {
    directoryAdmissions.check(new MapperDirectoryAdmission(this, task));
  }

  void directoryStorage(Map<String, Object> task, long additional) {
    if (!directoryTask(task)) return;
    io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryStorageLimit.check(
        directoryRetentionRecords.storageBytes(s(task, "tenant_id"), s(task, "id")), additional);
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot
      directorySnapshot(Lease lease) {
    return directoryService.directorySnapshot(lease);
  }

  @Override
  public void directoryReady(Lease lease) {
    directoryService.directoryReady(lease);
  }

  Map<String, Object> originalDirectorySlot(
      Lease lease, String stepId, Call call, String version, String digest) {
    return MapperDirectoryRecords.unwrap(
        directoryService.originalDirectorySlot(lease, stepId, call, version, digest));
  }

  Map<String, Object> freezeDirectorySlot(Map<String, Object> task, Map<String, Object> i) {
    return MapperDirectoryRecords.unwrap(
        directoryService.freezeDirectorySlot(
            MapperDirectoryRecords.wrap(task), MapperDirectoryRecords.wrap(i)));
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen freezeDirectory(
      Lease lease, String stepId, Call call, String version, String digest) {
    return directoryService.freezeDirectory(lease, stepId, call, version, digest);
  }

  @Override
  public io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result commitDirectory(
      Lease lease,
      String invocationId,
      io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Evidence evidence) {
    return directoryService.commitDirectory(lease, invocationId, evidence);
  }

  @Override
  public String directoryFilePath(Lease lease, String invocationId) {
    return directoryService.directoryFilePath(lease, invocationId);
  }

  @Override
  public Map<String, Object> getWorkingDirectory(Identity identity, String taskId) {
    return directoryService.getWorkingDirectory(identity, taskId);
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

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoProjectionService
      todoProjections =
          new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoProjectionServiceImpl();

  Map<String, Object> todoProjection(Map<String, Object> task) {
    return todoProjections.projection(new MapperTodoProjections(this, task)).values();
  }

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoEligibilityService
      todoEligibility =
          new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoEligibilityServiceImpl();

  private void todoAccess(Map<String, Object> task) {
    todoEligibility.access(new MapperTodoEligibility(this, task));
  }

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService
      todoStorageBudget =
          new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoStorageServiceImpl();

  void todoStorage(Map<String, Object> task, long additional) {
    todoStorageBudget.requireCapacity(new MapperTodoStorage(this, task), additional);
  }

  private final io.github.lovexiaopeng.jimu.kernel.execution.service.CommitFenceService
      commitFenceService =
          new io.github.lovexiaopeng.jimu.kernel.execution.service.impl.CommitFenceServiceImpl();

  void todoCommitFence(Lease lease) {
    commitFenceService.require(new MapperCommitFence(this, lease));
  }

  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskMetadataAccountingService
      taskMetadata =
          new io.github.lovexiaopeng.jimu.kernel.task.service.impl
              .TaskMetadataAccountingServiceImpl();

  private void directoryMetadata(Map<String, Object> task, boolean closing) {
    taskMetadata.directoryMetadata(new MapperTaskMetadataAccounting(this, task), closing);
  }

  private void todoMetadata(Map<String, Object> task, boolean closing) {
    taskMetadata.todoMetadata(new MapperTaskMetadataAccounting(this, task), closing);
  }

  @Override
  public io.github.lovexiaopeng.jimu.plugin.api.TodoPort.Result replaceOrResult(
      Lease lease, String stepId, Call call, String version, String digest) {
    return todoService.replaceOrResult(lease, stepId, call, version, digest);
  }

  <T> T todoSession(Lease lease, java.util.function.Supplier<T> work) {
    return db.execute(
        (org.springframework.jdbc.core.ConnectionCallback<T>)
            connection -> {
              int remaining =
                  (int) java.time.Duration.between(Instant.now(), lease.deadline()).toSeconds();
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

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoResultSourceService
      todoResultSources =
          new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoResultSourceServiceImpl();

  void todoResultSource(Map<String, Object> task, Map<String, Object> i) {
    todoResultSources.require(new MapperTodoResultSources(this, task, i));
  }

  @Override
  public Map<String, Object> getTodos(Identity identity, String taskId) {
    return todoService.getTodos(identity, taskId);
  }

  public void questionsEnabled(boolean enabled) {
    questionsEnabled = enabled;
  }

  private final io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryAvailabilityService
      directoryAvailability =
          new io.github.lovexiaopeng.jimu.kernel.directory.service.impl
              .DirectoryAvailabilityServiceImpl();

  private boolean directoryAvailable(Identity identity) {
    return directoryAvailability.available(new MapperDirectoryAvailability(this, identity));
  }

  private final io.github.lovexiaopeng.jimu.kernel.todo.service.TodoAvailabilityService
      todoAvailability =
          new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoAvailabilityServiceImpl();

  private boolean todoAvailable(Identity identity) {
    return todoAvailability.available(new MapperTodoAvailability(this, identity));
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

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionEligibilityService
      questionEligibility =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionEligibilityServiceImpl();

  private void questionAccess(Map<String, Object> task) {
    questionEligibility.access(new MapperQuestionEligibility(this, task));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionClockService
      questionClock =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionClockServiceImpl();

  private Instant questionNow() {
    return questionClock.now(new MapperQuestionClock(this));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionVisibilityService
      questionVisibility =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionVisibilityServiceImpl();

  private Map<String, Object> questionVisible(Identity identity, String taskId) {
    return questionVisibility.visible(new MapperQuestionVisibility(this, identity, taskId));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionListingService
      questionListing =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionListingServiceImpl();

  @Override
  public Map<String, Object> listQuestions(Identity identity, String taskId) {
    return questionListing.list(
        new MapperQuestionListing(
            this,
            identity,
            taskId,
            work -> tx(work),
            () -> questionVisible(identity, taskId),
            this::questionNow,
            (q, task) -> questionInput(q, task).questions()));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionInputService
      questionInputs =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionInputServiceImpl();

  private io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin.Input
      questionInput(Map<String, Object> q, Map<String, Object> task) {
    return questionInputs.input(new LedgerQuestionInput(this, q, task));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionAuthorizationService
      questionAuthorization =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionAuthorizationServiceImpl();

  @Override
  public void authorizeAnswer(Identity identity, String taskId, String requestId) {
    questionAuthorization.authorize(
        new MapperQuestionAuthorization(
            this,
            identity,
            taskId,
            requestId,
            work -> tx(work),
            () -> questionVisible(identity, taskId)));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionAnswerService
      questionAnswers =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionAnswerServiceImpl();

  @Override
  public Submission answer(
      Identity identity, String taskId, String requestId, String key, Command command) {
    return questionAnswers.answer(
        new MapperQuestionAnswer(
            this,
            identity,
            taskId,
            requestId,
            key,
            command,
            work -> tx(work),
            () -> questionVisible(identity, taskId),
            this::questionNow,
            (q, task) -> questionInput(q, task).questions(),
            (request, answers) -> questionAnswerDigest(request, command, answers),
            this::receipt,
            this::closeQuestions,
            this::continueQuestion,
            this::questionToolResult,
            this::questionResume));
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

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionOpeningService
      questionOpening =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionOpeningServiceImpl();

  @Override
  public OpenResult openOrResult(
      Lease lease, String step, Call call, String version, String digest) {
    return questionOpening.openOrResult(
        new MapperQuestionOpening(this, lease, step, call, version, digest));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionPauseService
      questionPausing =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionPauseServiceImpl();

  void questionPause(Map<String, Object> task, Lease lease, String state, String continuation) {
    questionPausing.pause(new MapperQuestionPause(this, lease), state, continuation);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionToolResultService
      questionToolCompletion =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionToolResultServiceImpl();

  private void questionToolResult(Map<String, Object> task, Map<String, Object> q, String output) {
    questionToolCompletion.complete(new MapperQuestionToolResult(this, task, q), output);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionResumeService
      questionResumption =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionResumeServiceImpl();

  private void questionResume(Map<String, Object> task, String requestId) {
    questionResumption.resume(new MapperQuestionResume(this, task), requestId);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionContinuationService
      questionContinuation =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionContinuationServiceImpl();

  private void continueQuestion(Map<String, Object> task, Map<String, Object> q) {
    questionContinuation.continueQuestion(
        new MapperQuestionContinuation(
            this,
            task,
            q,
            output -> questionToolResult(task, q, output),
            requestId -> questionResume(task, requestId)));
  }

  private void closeQuestions(Map<String, Object> task, String code) {
    taskOperations.closeQuestions(taskOperationRecords.row(task), code);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.PendingQuestionMessagesService
      pendingQuestionMessages =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .PendingQuestionMessagesServiceImpl();

  @Override
  public List<String> pendingQuestionMessages(Lease lease) {
    return pendingQuestionMessages.pending(lease, new LedgerPendingQuestionMessages(this));
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionMessagesService
      pendingQuestionReplies =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionMessagesServiceImpl();

  Map<String, String> questionMessages(Map<String, Object> task) {
    return pendingQuestionReplies.messages(
        new MapperQuestionMessages(this, task, q -> questionInput(q, task).questions()));
  }

  String encodeQuestionReply(
      int version,
      String kind,
      String tool,
      String requestId,
      String callId,
      java.util.List<io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Question> questions,
      com.fasterxml.jackson.databind.JsonNode answers) {
    return Payloads.json(
        new LateQuestionReply(version, kind, tool, requestId, callId, questions, answers));
  }

  @Override
  public boolean questionContinuation(Lease lease) {
    return leaseTx(lease, () -> fenced(lease, true).get("question_continuation_step_id") != null);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService
      questionStorageBudget =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionStorageServiceImpl();

  void questionStorage(Map<String, Object> task, long additional) {
    questionStorageBudget.requireCapacity(new MapperQuestionStorage(this, task), additional);
  }

  private final io.github.lovexiaopeng.jimu.kernel.question.service.QuestionMaintenanceService
      questionMaintenance =
          new io.github.lovexiaopeng.jimu.kernel.question.service.impl
              .QuestionMaintenanceServiceImpl();

  void maintainQuestions() {
    questionMaintenance.maintain(
        new MapperQuestionMaintenance(
            this, this::tx, this::closeQuestions, this::continueQuestion));
  }

  public void imageStorage(ArtifactStore store) {
    imageStore = store;
  }

  public void mcpSettings(io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings settings) {
    mcpRecords.configure(settings);
  }

  @Override
  public Binding mcpBinding(Lease lease) {
    return mcpService.mcpBinding(lease);
  }

  @Override
  public Server mcpServer(Lease lease, String server) {
    return mcpService.mcpServer(lease, server);
  }

  @Override
  public int mcpDispatch(
      Lease lease,
      String invocation,
      String method,
      String requestId,
      String digest,
      boolean cleanup) {
    return mcpService.mcpDispatch(lease, invocation, method, requestId, digest, cleanup);
  }

  @Override
  public void mcpResponded(Lease lease, String invocation, int ordinal, String error) {
    mcpService.mcpResponded(lease, invocation, ordinal, error);
  }

  @Override
  public void reserveMcpList(Lease lease, String invocation) {
    mcpService.reserveMcpList(lease, invocation);
  }

  @Override
  public String mcpPage(Lease lease, String query, String cursor) {
    return mcpService.mcpPage(lease, query, cursor);
  }

  @Override
  public void mcpSucceeded(Lease lease, String invocation, String output, ListSnapshot snapshot) {
    mcpService.mcpSucceeded(lease, invocation, output, snapshot);
  }

  private void cleanupMcp() {
    mcpRetention.cleanup();
  }

  void accountSession(Map<String, Object> task) {
    sessions.account(task, false);
  }

  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ImageAvailabilityService
      imageAvailability =
          new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl
              .ImageAvailabilityServiceImpl();

  boolean imagesAvailable() {
    return imageAvailability.available(
        new LedgerImageAvailability(() -> imageStore != null, () -> imageStore.check()));
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
    this(
        LedgerPersistenceDependencies.create(
            source, manager, unitOfWork, schemaCheck, CoreMappers.from(sessions)),
        models,
        null);
  }

  private JdbcLedger(
      LedgerPersistenceDependencies persistence,
      ModelProfiles models,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    this.persistence = persistence;
    source = persistence.source();
    mappers = persistence.mappers();
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
    imageRecords = new MapperImageRecords(this);
    imageService =
        new io.github.lovexiaopeng.jimu.kernel.media.service.impl.ImagePersistenceServiceImpl(
            imageRecords);
    mcpRecords = new MapperMcpRecords(this);
    mcpService =
        new io.github.lovexiaopeng.jimu.kernel.mcp.service.impl.McpPersistenceServiceImpl(
            mcpRecords);
    nativeSearchService =
        new io.github.lovexiaopeng.jimu.kernel.search.service.impl
            .NativeSearchPersistenceServiceImpl(new MapperNativeSearchRecords(this));
    directoryService =
        new io.github.lovexiaopeng.jimu.kernel.directory.service.impl
            .DirectoryPersistenceServiceImpl(new MapperDirectoryRecords(this));
    todoService =
        new io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoPersistenceServiceImpl(
            new MapperTodoRecords(this));
    materialEvidence =
        new io.github.lovexiaopeng.jimu.kernel.model.service.impl.MaterialEvidenceServiceImpl(
            new MapperMaterialEvidenceRecords(this));
    db.setQueryTimeout(5);
    scopeConsumption =
        new io.github.lovexiaopeng.jimu.kernel.operator.service.impl
            .ExecutionScopeConsumptionServiceImpl(new MapperExecutionScopeConsumption(this, db));
    queryTransactions =
        new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction(
            source, persistence.manager());
    transactions = new TransactionTemplate(persistence.manager());
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
    unitOfWork = persistence.unitOfWork();
    schemaCheck = persistence.schemaCheck();
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
            new JdbcExecutionRecords(this, db), coreWork, leaseWork, Payloads::hash);
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
            persistence,
            models,
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(end, resources));
    QueryViewConfiguration.capture(this).apply(scoped);
    return scoped;
  }

  /** Capture after view construction, without evaluating availability, stores or credentials. */
  private record QueryViewConfiguration(
      java.util.function.BooleanSupplier searchAvailable,
      ArtifactStore imageStore,
      boolean questionsEnabled,
      boolean todosEnabled,
      boolean directoryEnabled,
      io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore directoryWorkspace,
      boolean sessionReadEnabled,
      boolean sessionSearchEnabled,
      boolean sessionTraceEnabled,
      boolean priorSessionSearchEnabled,
      JdbcSessionRecords.Configuration session,
      io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings mcp) {
    static QueryViewConfiguration capture(JdbcLedger ledger) {
      return new QueryViewConfiguration(
          ledger.searchAvailable,
          ledger.imageStore,
          ledger.questionsEnabled,
          ledger.todosEnabled,
          ledger.directoryEnabled,
          ledger.directoryWorkspace,
          ledger.sessionReadEnabled,
          ledger.sessionSearchEnabled,
          ledger.sessionTraceEnabled,
          ledger.priorSessionSearchEnabled,
          ledger.sessions.configuration(),
          ledger.mcpRecords.configuration());
    }

    void apply(JdbcLedger ledger) {
      ledger.searchAvailable = searchAvailable;
      ledger.imageStore = imageStore;
      ledger.questionsEnabled = questionsEnabled;
      ledger.todosEnabled = todosEnabled;
      ledger.directoryEnabled = directoryEnabled;
      ledger.directoryWorkspace = directoryWorkspace;
      ledger.sessionReadEnabled = sessionReadEnabled;
      ledger.sessionSearchEnabled = sessionSearchEnabled;
      ledger.sessionTraceEnabled = sessionTraceEnabled;
      ledger.priorSessionSearchEnabled = priorSessionSearchEnabled;
      ledger.sessions.configure(session);
      ledger.mcpRecords.configure(mcp);
    }
  }

  private JdbcLedger queryView(Lease lease, boolean metered) {
    long now = System.nanoTime();
    return queryView(
        Math.min(
            now + 2_000_000_000L,
            now + java.time.Duration.between(Instant.now(), lease.deadline()).toNanos()),
        metered ? new QueryResources(0, 0, 0) : null);
  }

  <T> T tx(Supplier<T> work) {
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
    return imageService.mediaSnapshot(lease);
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.Raw imageInput(
      Lease lease, String path) {
    return imageService.imageInput(lease, path);
  }

  @Override
  public String reserveImage(Lease lease, String invocation, String inputId) {
    return imageService.reserveImage(lease, invocation, inputId);
  }

  @Override
  public void imageSucceeded(
      Lease lease,
      String invocation,
      String key,
      io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result result) {
    imageService.imageSucceeded(lease, invocation, key, result);
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ObjectValue imageObject(
      Lease lease, io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result ref) {
    return imageService.imageObject(lease, ref);
  }

  @Override
  public List<io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ObjectValue> stepImages(
      Lease lease, String step) {
    return imageService.stepImages(lease, step);
  }

  @Override
  public void imageDecodeTimedOut(Lease lease) {
    imageService.imageDecodeTimedOut(lease);
  }

  String requireScope(
      String tenant,
      Map<String, Object> evaluation,
      Map<String, Object> task,
      ModelSnapshot model) {
    return scopeConsumption.requireScope(tenant, evaluation, task, model);
  }

  boolean businessTask(Map<String, Object> task) {
    return scopeConsumption.businessTask(task);
  }

  private void requireBusiness(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    scopeConsumption.requireBusiness(tenant, run, task, model);
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

  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskIdentityService taskIdentity =
      new io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskIdentityServiceImpl();

  void identity(Identity identity) {
    taskIdentity.identity(new MapperTaskIdentity(this), identity);
  }

  long epoch(Identity identity, List<String> capabilities) {
    return taskIdentity.epoch(new MapperTaskIdentity(this), identity, capabilities);
  }

  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskCapabilitiesService
      taskCapabilities =
          new io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskCapabilitiesServiceImpl();

  @Override
  public List<String> capabilities(Identity identity) {
    return taskCapabilities.discover(
        new MapperTaskCapabilities(
            this,
            identity,
            this::tx,
            () -> directoryAvailable(identity),
            () -> todoAvailable(identity),
            search -> sessionAvailable(identity, search),
            () -> traceAvailable(identity),
            () -> priorAvailable(identity)));
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

  private final io.github.lovexiaopeng.jimu.kernel.task.service.TaskBaseAccessService
      taskBaseAccess =
          new io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskBaseAccessServiceImpl();

  private void baseAccess(Map<String, Object> row) {
    taskBaseAccess.require(
        new MapperTaskBaseAccess(
            this,
            row,
            () -> sessionAccess(row),
            () -> directoryAccess(row),
            () -> todoAccess(row),
            () -> questionAccess(row)));
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
        evaluation(tenant, scopeConsumption.targetRun(tenant, s(candidate, "evaluation_run_id")));
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
    return MapperMaterialEvidenceRecords.unwrap(
        materialEvidence.material(MapperMaterialEvidenceRecords.wrap(task)));
  }

  void checkMaterials(Map<String, Object> task) {
    materialEvidence.checkMaterials(MapperMaterialEvidenceRecords.wrap(task));
  }

  @Override
  public Optional<String> evaluationFile(Lease lease) {
    return materialEvidence.evaluationFile(lease);
  }

  ReportFile initialFile(Map<String, Object> task) {
    return materialEvidence.initialFile(MapperMaterialEvidenceRecords.wrap(task));
  }

  @Override
  public void bindEvaluationFile(Lease lease, ReportFile file) {
    materialEvidence.bindEvaluationFile(lease, file);
  }

  void materialBody(Map<String, Object> task, String request, int ordinal) {
    materialEvidence.materialBody(MapperMaterialEvidenceRecords.wrap(task), request, ordinal);
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
        lease.tenant(), admission ? scopeConsumption.targetRun(lease.tenant(), run) : run);
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

  private final io.github.lovexiaopeng.jimu.kernel.invocation.service.WebFetchService
      webFetchService =
          new io.github.lovexiaopeng.jimu.kernel.invocation.service.impl.WebFetchServiceImpl(
              new MapperWebFetchRecords(this));

  @Override
  public void observeWebFetch(Lease lease, String invocationId, WebFetchObservation observation) {
    webFetchService.observe(lease, invocationId, observation);
  }

  @Override
  public void preserveWebFetchResult(Lease lease, String invocationId, String output) {
    webFetchService.preserve(lease, invocationId, output);
  }

  @Override
  public void toolSucceeded(Lease lease, String invocationId, String output) {
    invocationService.succeeded(lease, invocationId, output);
  }

  void commitTool(Lease lease, String invocationId, String output, boolean mcpCommit) {
    invocationService.commit(lease, invocationId, output, mcpCommit);
  }

  @Override
  public void uncertain(Lease lease, String invocationId) {
    invocationService.uncertain(lease, invocationId);
  }

  @Override
  public boolean searchQuiescent(Lease lease, String invocation) {
    return nativeSearchService.searchQuiescent(lease, invocation);
  }

  @Override
  public void prepareSearch(
      Lease lease,
      Invocation invocation,
      String directory,
      String snapshot,
      String query,
      String pin) {
    nativeSearchService.prepareSearch(lease, invocation, directory, snapshot, query, pin);
  }

  @Override
  public void searchStarted(Lease lease, String invocation, NativeIdentity identity) {
    nativeSearchService.searchStarted(lease, invocation, identity);
  }

  @Override
  public void searchStopped(Run run, NativeIdentity identity) {
    nativeSearchService.searchStopped(run, identity);
  }

  @Override
  public void searchCompleted(
      Lease lease, String invocation, String hash, long size, Map<String, Integer> cursors) {
    nativeSearchService.searchCompleted(lease, invocation, hash, size, cursors);
  }

  @Override
  public Result searchPage(Lease lease, String query, String pin, String cursor) {
    return nativeSearchService.searchPage(lease, query, pin, cursor);
  }

  @Override
  public List<Run> nativeSearches(String host) {
    return nativeSearchService.nativeSearches(host);
  }

  @Override
  public List<Run> expiredSearches(String host) {
    return nativeSearchService.expiredSearches(host);
  }

  @Override
  public void searchStorageRemoved(Run run) {
    nativeSearchService.searchStorageRemoved(run);
  }

  boolean publicReport(Map<String, Object> task) {
    if (directoryTask(task)) return false;
    var pins = tools(task).pins();
    var pin = pins.get("web_fetch");
    return pin != null && "0.3.0".equals(pin.version())
        || pins.keySet().stream()
            .anyMatch(name -> List.of("read", "write", "present", "compose_report").contains(name));
  }

  private final MapperReportContexts reportContexts = new MapperReportContexts(this);
  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ReportContextService
      reportContextService =
          new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ReportContextServiceImpl(
              reportContexts);

  @Override
  public ReportContext reportContext(Lease lease) {
    return reportContextService.context(lease);
  }

  ReportContext reportContext(Map<String, Object> task) {
    return reportContextService.context(reportContexts.wrap(task));
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Lease lease, String invocation) {
    return materialEvidence.imageReportContext(lease, invocation);
  }

  io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Map<String, Object> task, String invocation) {
    return materialEvidence.imageReportContext(
        MapperMaterialEvidenceRecords.wrap(task), invocation);
  }

  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ReportPublicationService
      reportPublications =
          new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ReportPublicationServiceImpl(
              new MapperReportPublications(this));

  @Override
  public List<Upload> stageReports(Lease lease, String invocationId, List<ReportFile> files) {
    return reportPublications.stage(lease, invocationId, files);
  }

  @Override
  public void presentSucceeded(
      Lease lease, String invocationId, List<Publication> publications, String output) {
    reportPublications.succeed(lease, invocationId, publications, output);
  }

  @Override
  public void toolFailed(Lease lease, String invocationId, String code) {
    invocationService.failed(lease, invocationId, code);
  }

  private final io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactPublicationService
      artifactPublications =
          new io.github.lovexiaopeng.jimu.kernel.artifact.service.impl
              .ArtifactPublicationServiceImpl(new MapperArtifactPublications(this));

  @Override
  public Upload stage(Lease lease, String hash, long size) {
    return artifactPublications.stage(lease, hash, size);
  }

  @Override
  public void verified(Lease lease, String artifactId, String key) {
    artifactPublications.verified(lease, artifactId, key);
  }

  @Override
  public void publish(Lease lease, String artifactId) {
    artifactPublications.publish(lease, artifactId);
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
