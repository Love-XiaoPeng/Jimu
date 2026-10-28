package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork.Dependency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.reflection.SystemMetaObject;

/** 审过的具名DML登记；未知语句、MP wrapper或冲突键不能按SQL拼写默认放行。 */
final class SessionDependencyWrites {
  enum Kind {
    SCALAR,
    TASK,
    PROGRESS,
    STEP,
    DISPATCH,
    INSTALLATION,
    UNKNOWN
  }

  private static final String ROOT = "io.github.lovexiaopeng.jimu.adapters.persistence.";
  private static final Map<String, Kind> WRITES = registry();

  private SessionDependencyWrites() {}

  private static void add(Map<String, Kind> into, String mapper, Kind kind, String names) {
    for (String name : names.split(" ")) {
      if (into.put(ROOT + mapper + "." + name, kind) != null)
        throw new IllegalStateException("Duplicate session write registration");
    }
  }

  private static Map<String, Kind> registry() {
    var r = new HashMap<String, Kind>();
    add(
        r,
        "task.mapper.TaskAdmissionMapper",
        Kind.TASK,
        "insertAccepted bindSnapshots bindQuestion bindMcp bindPolicy bindEvaluation bindMaterial");
    add(
        r,
        "task.mapper.TaskAdmissionMapper",
        Kind.SCALAR,
        "holdHistory insertDirectory holdDirectory insertTodo holdTodo holdQuestion holdImages settleImages setTaskCost holdQueue");
    add(
        r,
        "task.mapper.TaskMapper",
        Kind.TASK,
        "saveMediaSnapshot bindMaterialReceipt clearQuestionContinuation");
    add(
        r,
        "task.mapper.TaskMapper",
        Kind.PROGRESS,
        "renewOwnedLease expireStoppedWorkerLease assignClaimedLease advanceEventSequence finishSummary adjustModelCost reserveModelTokens releaseModelTokens stopFurtherModelDispatch reserveToolUnit commitLiveFence cancelImageDecode countDirectoryTool countTodoTool");
    add(
        r,
        "task.mapper.TaskOperationsMapper",
        Kind.PROGRESS,
        "requestCancelIntent requestCancel setTerminal markUnknownInvocations holdUnknownTools holdUnknownModels clearQuestionContinuation");
    add(
        r,
        "task.mapper.TaskOperationsMapper",
        Kind.SCALAR,
        "releaseQueue releaseRun releaseQuestionTask");
    add(
        r,
        "execution.mapper.CommitFenceMapper",
        Kind.SCALAR,
        "commitFence commitRetainedFence admissionFence terminalRetainedFence settlementFence stopSettlementFence");
    add(
        r,
        "execution.mapper.TaskAttemptMapper",
        Kind.PROGRESS,
        "markOwnedAttemptQuiescent markWorkerTaskAttemptsQuiescent expireActiveAttempt insertActiveAttempt");
    add(r, "execution.mapper.StepMapper", Kind.PROGRESS, "insertPreparedStep");
    add(
        r,
        "execution.mapper.StepMapper",
        Kind.STEP,
        "markModelRunning rejectModelStep storeModelResponse bindDirectoryProjection");
    add(
        r,
        "invocation.mapper.InvocationMapper",
        Kind.PROGRESS,
        "insert markRunningEffectsUnknown bindTodoRevision succeedRunning failRunning markInvocationUnknown preserveWebResult");
    add(r, "model.mapper.ModelDispatchMapper", Kind.PROGRESS, "insert failOriginalDispatch");
    add(
        r,
        "model.mapper.ModelDispatchMapper",
        Kind.DISPATCH,
        "recordRejection rejectOversizedResponse recordActualUsage");
    add(r, "budget.mapper.ReservationMapper", Kind.PROGRESS, "insertReservation");
    add(
        r,
        "budget.mapper.ReservationMapper",
        Kind.DISPATCH,
        "settleModelTokens holdModelUnknown holdReservedModelUnknown holdFailedModelUnknown holdInvalidModelProof settleActualModelCost holdCostUnknown");
    add(
        r,
        "budget.mapper.TenantBudgetMapper",
        Kind.SCALAR,
        "moveQueuedToRunning incrementRunning holdMediaCheckpoint adjustModelCost reserveModelTokens releaseModelTokens reserveToolUnit holdImageStorage releaseImageStorage holdMcpStorage releaseMcpStorage holdSearchStorage releaseCompletedSearchStorage releaseRemovedSearchStorage countDirectoryBudget countTodoBudget");
    add(r, "session.mapper.SessionHistoryStorageMapper", Kind.TASK, "create");
    add(r, "session.mapper.SessionHistoryStorageMapper", Kind.PROGRESS, "body");
    add(r, "session.mapper.SessionEventAppendMapper", Kind.PROGRESS, "advance");
    add(r, "session.mapper.SessionContextMapper", Kind.SCALAR, "account");
    add(
        r,
        "session.mapper.SessionScopeMapper",
        Kind.TASK,
        "defer invalidate invalidateAndSchedule insertAuthority insertAuthorityTarget");
    add(r, "session.mapper.SessionScopeRevocationMapper", Kind.TASK, "revoke");
    add(r, "session.mapper.SessionLineageMapper", Kind.TASK, "insert update delete purge");
    add(
        r,
        "session.mapper.SessionHistoryRetentionMapper",
        Kind.TASK,
        "purgeLineageTrace purgeScopeQuery purgeScopeStep purgeScopeTargets purgeScope purgePublicEvents purgeSearch purgeTrace purgeRead purgeInvocations purgeSteps purgeDispatches purgeTask markPurged retainAuthorityHistory");
    add(
        r,
        "session.mapper.SessionHistoryRetentionMapper",
        Kind.SCALAR,
        "settleHistory settleReservation releaseBudget");
    add(r, "session.mapper.SessionScopeQueryMapper", Kind.PROGRESS, "consumeTask");
    add(r, "session.mapper.SessionScopeQueryMapper", Kind.SCALAR, "consumeTenant");
    add(r, "plugin.mapper.InstallationMapper", Kind.INSTALLATION, "insert update delete");
    return Map.copyOf(r);
  }

  static Kind kind(String id) {
    return WRITES.getOrDefault(id, Kind.UNKNOWN);
  }

  private static boolean entity(Object value) {
    return value
            instanceof
            io.github.lovexiaopeng.jimu.adapters.persistence.model.entity.ModelDispatchEntity
        || value
            instanceof
            io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity.InvocationEntity
        || value
            instanceof
            io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity
        || value
            instanceof
            io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
  }

  private static List<Map<?, ?>> fields(Object parameter) {
    var fields = new java.util.ArrayList<Map<?, ?>>();
    if (parameter instanceof Map<?, ?> map) {
      fields.add(map);
      for (String key : List.of("et", "param1"))
        if (map.containsKey(key) && entity(map.get(key))) fields.add(properties(map.get(key)));
    } else if (entity(parameter)) fields.add(properties(parameter));
    return fields;
  }

  private static Map<String, Object> properties(Object value) {
    var meta = SystemMetaObject.forObject(value);
    var fields = new HashMap<String, Object>();
    for (String key : meta.getGetterNames()) fields.put(key, meta.getValue(key));
    return fields;
  }

  /** 所有显式别名必须一致，缺全键/相互冲突都按未知处理。 */
  private static String key(List<Map<?, ?>> fields, String... aliases) {
    String found = null;
    for (var map : fields)
      for (String name : aliases) {
        if (!map.containsKey(name) || map.get(name) == null) continue;
        if (!(map.get(name) instanceof String text) || text.isEmpty()) return null;
        if (found != null && !found.equals(text)) return null;
        found = text;
      }
    return found;
  }

  static Set<Dependency> keys(Object parameter, Kind kind, SessionQueryWork owner) {
    var fields = fields(parameter);
    String tenant = key(fields, "tenant", "tenantId", "tenant_id");
    if (tenant == null) return null;
    if (kind == Kind.INSTALLATION) {
      String plugin = key(fields, "plugin", "pluginId", "plugin_id");
      return plugin == null ? null : Set.of(Dependency.installation(tenant, plugin));
    }
    String task =
        key(fields, "caller", "taskId", "task", "id", "callerTaskId", "caller_task_id", "task_id");
    // 显式task候选冲突绝不可退到当前lease猜测。
    boolean explicit =
        fields.stream()
            .anyMatch(
                m ->
                    List.of(
                            "caller",
                            "taskId",
                            "task",
                            "id",
                            "callerTaskId",
                            "caller_task_id",
                            "task_id")
                        .stream()
                        .anyMatch(m::containsKey));
    if (task != null) return Set.of(new Dependency(tenant, task));
    if (explicit || owner == null) return null;
    if (kind == Kind.STEP)
      return owner.boundProgress(tenant, key(fields, "step", "stepId", "step_id"), null);
    if (kind == Kind.DISPATCH)
      return owner.boundProgress(
          tenant, null, key(fields, "dispatch", "dispatchId", "reservationId", "reservation"));
    return null;
  }

  static void before(String id, Object parameter, SessionQueryWork owner) {
    Kind kind = kind(id);
    if (kind == Kind.SCALAR) {
      owner.mutation();
      return;
    }
    var keys = kind == Kind.UNKNOWN ? null : keys(parameter, kind, owner);
    if ((kind == Kind.PROGRESS || kind == Kind.STEP || kind == Kind.DISPATCH)
        && owner.callerProgress(keys)) owner.mutation();
    else owner.dependencyMutation(keys);
  }
}
