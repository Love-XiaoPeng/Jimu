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
    INVOCATION,
    ATTEMPT,
    RESERVATION,
    TOOL_RESERVATION,
    BINDING,
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
        Kind.ATTEMPT,
        "markOwnedAttemptQuiescent expireActiveAttempt");
    add(
        r,
        "execution.mapper.TaskAttemptMapper",
        Kind.PROGRESS,
        "markWorkerTaskAttemptsQuiescent insertActiveAttempt");
    add(r, "execution.mapper.StepMapper", Kind.PROGRESS, "insertPreparedStep");
    add(
        r,
        "execution.mapper.StepMapper",
        Kind.STEP,
        "markModelRunning rejectModelStep storeModelResponse bindDirectoryProjection");
    add(r, "invocation.mapper.InvocationMapper", Kind.PROGRESS, "markRunningEffectsUnknown");
    add(r, "invocation.mapper.InvocationMapper", Kind.STEP, "insert bindTodoRevision");
    add(
        r,
        "invocation.mapper.InvocationMapper",
        Kind.INVOCATION,
        "markAdmittedRunning succeedRunning failRunning markInvocationUnknown preserveWebResult");
    add(r, "model.mapper.ModelDispatchMapper", Kind.STEP, "insert");
    add(r, "model.mapper.ModelDispatchMapper", Kind.DISPATCH, "failOriginalDispatch");
    add(
        r,
        "model.mapper.ModelDispatchMapper",
        Kind.DISPATCH,
        "recordRejection rejectOversizedResponse recordActualUsage");
    add(r, "budget.mapper.ReservationMapper", Kind.PROGRESS, "insertReservation");
    add(
        r,
        "budget.mapper.ReservationMapper",
        Kind.RESERVATION,
        "settleModelTokens holdModelUnknown holdReservedModelUnknown settleActualModelCost holdCostUnknown");
    add(
        r,
        "budget.mapper.ReservationMapper",
        Kind.DISPATCH,
        "holdFailedModelUnknown holdInvalidModelProof");
    add(r, "budget.mapper.ReservationMapper", Kind.TOOL_RESERVATION, "settleToolUnit");
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
    add(r, "session.mapper.SessionScopeQueryMapper", Kind.TOOL_RESERVATION, "settle");
    add(
        r,
        "session.mapper.SessionScopeQueryMapper",
        Kind.INVOCATION,
        "finishInvocation commitQuery");
    add(r, "session.mapper.SessionLineageTraceMapper", Kind.INVOCATION, "commit");
    add(r, "session.mapper.ScopeQueryStepReadMapper", Kind.STEP, "bindPrepared");
    add(r, "session.mapper.SessionPreparedScopeMapper", Kind.STEP, "insert");
    for (String mapper :
        List.of(
            "SessionReadBindingMapper",
            "SessionSearchBindingMapper",
            "SessionTraceBindingMapper",
            "SessionScopeBindingMapper",
            "SessionLineageTraceMapper"))
      add(r, "session.mapper." + mapper, Kind.BINDING, "insert");
    add(r, "event.mapper.EventMapper", Kind.PROGRESS, "insertFact");
    add(r, "session.mapper.SessionPublicEventAppendMapper", Kind.PROGRESS, "insert");
    add(r, "task.mapper.TaskOperationsMapper", Kind.RESERVATION, "releaseReservation");
    add(r, "task.mapper.TaskOperationsMapper", Kind.ATTEMPT, "stopAttempt");
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
            io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity
        || List.of(
                "SessionPreparedScopeEntity",
                "SessionReadBindingEntity",
                "SessionSearchBindingEntity",
                "SessionTraceBindingEntity",
                "SessionScopeBindingEntity",
                "SessionLineageTraceEntity",
                "SessionPublicEventAppendEntity")
            .stream()
            .anyMatch(
                name ->
                    value != null
                        && value.getClass().getName().equals(ROOT + "session.entity." + name));
  }

  /** Borrow original parameter/scalars; no reflection property Map or getter-name array. */
  private static final class Fields {
    private final SessionQueryWork owner;
    private final Map<?, ?> map;
    private final org.apache.ibatis.reflection.MetaObject first, second;

    Fields(Object parameter, SessionQueryWork owner) {
      this.owner = owner;
      map = parameter instanceof Map<?, ?> m ? m : Map.of();
      Object a = map.containsKey("et") ? map.get("et") : parameter;
      Object b = map.containsKey("param1") ? map.get("param1") : null;
      first = entity(a) ? SystemMetaObject.forObject(a) : null;
      second = b != a && entity(b) ? SystemMetaObject.forObject(b) : null;
    }

    Object value(int index, String name) {
      if (owner != null) owner.inspect(1);
      if (index == 0) return map.containsKey(name) ? map.get(name) : null;
      var meta = index == 1 ? first : second;
      return meta != null && meta.hasGetter(name) ? meta.getValue(name) : null;
    }

    boolean supplied(String... names) {
      for (int i = 0; i < 3; i++) for (String name : names) if (value(i, name) != null) return true;
      return false;
    }
  }

  private static Fields fields(Object parameter, SessionQueryWork owner) {
    return new Fields(parameter, owner);
  }

  /** 所有显式别名必须一致，缺全键/相互冲突都按未知处理。 */
  private static String key(Fields fields, String... aliases) {
    String found = null;
    for (int i = 0; i < 3; i++)
      for (String name : aliases) {
        Object value = fields.value(i, name);
        if (value == null) continue;
        if (!(value instanceof String text) || text.isEmpty()) return null;
        if (found != null) {
          if (fields.owner != null) fields.owner.inspect(Math.max(found.length(), text.length()));
          if (!found.equals(text)) return null;
        }
        found = text;
      }
    return found;
  }

  static Set<Dependency> keys(Object parameter, Kind kind, SessionQueryWork owner) {
    // MP的实体是SET值，不是WHERE证明；尚未登记的wrapper条件一律保守失效。
    if (parameter instanceof Map<?, ?> map && map.containsKey("ew")) return null;
    var fields = fields(parameter, owner);
    String tenant = key(fields, "tenant", "tenantId", "tenant_id");
    if (tenant == null) return null;
    if (kind == Kind.INSTALLATION) {
      String plugin = key(fields, "plugin", "pluginId", "plugin_id");
      return plugin == null ? null : Set.of(Dependency.installation(tenant, plugin));
    }
    // SQL以step/dispatch等主键写时，附带task候选只能用于交叉检查，绝不能替代真实关系。
    if (kind == Kind.STEP
        || kind == Kind.DISPATCH
        || kind == Kind.INVOCATION
        || kind == Kind.ATTEMPT
        || kind == Kind.RESERVATION
        || kind == Kind.TOOL_RESERVATION
        || kind == Kind.BINDING) return null;
    String task =
        key(fields, "caller", "taskId", "task", "id", "callerTaskId", "caller_task_id", "task_id");
    // 显式task候选冲突绝不可退到当前lease猜测。
    boolean explicit =
        fields.supplied(
            "caller", "taskId", "task", "id", "callerTaskId", "caller_task_id", "task_id");
    if (task != null) return Set.of(new Dependency(tenant, task));
    if (explicit || owner == null) return null;
    return null;
  }

  static void before(String id, Object parameter, SessionQueryWork owner) {
    owner.extraction(true);
    try {
      beforeReserved(id, parameter, owner);
    } finally {
      owner.extraction(false);
    }
  }

  private static void beforeReserved(String id, Object parameter, SessionQueryWork owner) {
    Kind kind = kind(id);
    if (kind == Kind.SCALAR) {
      owner.mutation();
      return;
    }
    var keys = relationKeys(id, parameter, kind, owner);
    if ((kind == Kind.PROGRESS
            || kind == Kind.STEP
            || kind == Kind.DISPATCH
            || kind == Kind.INVOCATION
            || kind == Kind.ATTEMPT
            || kind == Kind.RESERVATION
            || kind == Kind.TOOL_RESERVATION
            || kind == Kind.BINDING)
        && owner.callerProgress(keys)) owner.mutation();
    else owner.dependencyMutation(keys);
  }

  private static Set<Dependency> relationKeys(
      String id, Object parameter, Kind kind, SessionQueryWork owner) {
    if (kind == Kind.UNKNOWN) return null;
    if (parameter instanceof Map<?, ?> map && map.containsKey("ew")) return null;
    var f = fields(parameter, owner);
    String tenant = key(f, "tenant", "tenantId", "tenant_id");
    String task = key(f, "caller", "taskId", "task", "callerTaskId", "caller_task_id", "task_id");
    boolean supplied =
        f.supplied("caller", "taskId", "task", "callerTaskId", "caller_task_id", "task_id");
    if (supplied && task == null) return null;
    var type =
        switch (kind) {
          case STEP -> SessionQueryWork.Relation.STEP;
          case DISPATCH -> SessionQueryWork.Relation.DISPATCH;
          case INVOCATION, BINDING -> SessionQueryWork.Relation.INVOCATION;
          case ATTEMPT -> SessionQueryWork.Relation.ATTEMPT;
          case RESERVATION, TOOL_RESERVATION -> SessionQueryWork.Relation.RESERVATION;
          default -> null;
        };
    if (type == null) return keys(parameter, kind, owner);
    String row =
        switch (type) {
          case STEP -> key(f, "stepId", "step_id", "step");
          case DISPATCH -> key(f, "dispatchId", "dispatch");
          case ATTEMPT -> key(f, "attemptId", "attempt");
          case RESERVATION ->
              kind == Kind.TOOL_RESERVATION
                  ? key(f, "invocation", "reservationId")
                  : key(f, "reservationId", "reservation");
          case INVOCATION ->
              id.equals(ROOT + "session.mapper.SessionLineageTraceMapper.commit")
                  ? key(f, "id")
                  : key(f, "invocationId", "invocation_id", "invocation");
        };
    // INSERT binding具有完整三键；不能只凭一条task关系将错误step绑定到已存在invocation。
    String parent = kind == Kind.BINDING ? key(f, "stepId", "step_id", "step") : null;
    if (kind == Kind.BINDING && (task == null || parent == null)) return null;
    String category = kind == Kind.TOOL_RESERVATION ? "TOOL" : null;
    if (kind == Kind.RESERVATION && !id.endsWith("TaskOperationsMapper.releaseReservation"))
      category =
          id.endsWith("settleActualModelCost") || id.endsWith("holdCostUnknown")
              ? "MODEL_COST"
              : "MODEL";
    String related = owner.related(type, tenant, row, task, parent, category);
    return related == null ? null : Set.of(new Dependency(tenant, related));
  }

  /** 具名SELECT的完整行、成功INSERT的真实字段是关系证据；wrapper条件不被解析。 */
  static void observed(String id, Object parameter, Object result, SessionQueryWork owner) {
    if (!(result instanceof Number) && !READS.containsKey(id)) return;
    owner.extraction(true);
    try {
      observeReserved(id, parameter, result, owner);
    } finally {
      owner.extraction(false);
    }
  }

  private static void observeReserved(
      String id, Object parameter, Object result, SessionQueryWork owner) {
    if (result instanceof Number changed) {
      if (changed.intValue() != 1) return;
      var f = fields(parameter, owner);
      String tenant = key(f, "tenant", "tenantId");
      String task = key(f, "taskId", "callerTaskId");
      if (id.equals(ROOT + "execution.mapper.StepMapper.insertPreparedStep"))
        owner.observed(SessionQueryWork.Relation.STEP, tenant, key(f, "stepId"), task, null, null);
      else if (id.equals(ROOT + "invocation.mapper.InvocationMapper.insert"))
        owner.observed(
            SessionQueryWork.Relation.INVOCATION,
            tenant,
            key(f, "invocationId"),
            task,
            key(f, "stepId"),
            null);
      else if (id.equals(ROOT + "model.mapper.ModelDispatchMapper.insert"))
        owner.observed(
            SessionQueryWork.Relation.DISPATCH,
            tenant,
            key(f, "dispatchId"),
            task,
            key(f, "stepId"),
            null);
      else if (id.equals(ROOT + "execution.mapper.TaskAttemptMapper.insertActiveAttempt"))
        owner.observed(
            SessionQueryWork.Relation.ATTEMPT, tenant, key(f, "attemptId"), task, null, null);
      else if (id.equals(ROOT + "budget.mapper.ReservationMapper.insertReservation"))
        owner.observed(
            SessionQueryWork.Relation.RESERVATION,
            tenant,
            key(f, "reservationId"),
            task,
            key(f, "dispatchId"),
            key(f, "category"));
      return;
    }
    SessionQueryWork.Relation type = READS.get(id);
    if (type == null || !(result instanceof List<?> rows)) return;
    for (Object value : rows)
      if (value instanceof Map<?, ?> row) {
        var f = fields(row, owner);
        owner.observed(
            type,
            key(f, "tenant_id"),
            key(f, "id"),
            key(f, "task_id"),
            type == SessionQueryWork.Relation.INVOCATION
                    || type == SessionQueryWork.Relation.DISPATCH
                ? key(f, "step_id")
                : type == SessionQueryWork.Relation.RESERVATION ? key(f, "dispatch_id") : null,
            type == SessionQueryWork.Relation.RESERVATION ? key(f, "category") : null);
      }
  }

  private static final Map<String, SessionQueryWork.Relation> READS = reads();

  private static Map<String, SessionQueryWork.Relation> reads() {
    var r = new HashMap<String, SessionQueryWork.Relation>();
    for (String name : List.of("selectLatestStep", "selectModelStep", "selectInvocationStep"))
      r.put(ROOT + "execution.mapper.StepMapper." + name, SessionQueryWork.Relation.STEP);
    r.put(
        ROOT + "session.mapper.ScopeQueryStepReadMapper.selectMaps",
        SessionQueryWork.Relation.STEP);
    r.put(
        ROOT + "session.mapper.HistoryInvocationReadMapper.selectMaps",
        SessionQueryWork.Relation.INVOCATION);
    for (String name : List.of("selectOriginalSlot", "selectTaskInvocation"))
      r.put(
          ROOT + "invocation.mapper.InvocationMapper." + name,
          SessionQueryWork.Relation.INVOCATION);
    r.put(
        ROOT + "session.mapper.ScopeQueryInvocationReadMapper.selectMaps",
        SessionQueryWork.Relation.INVOCATION);
    r.put(
        ROOT + "model.mapper.ModelDispatchMapper.selectSettlementProof",
        SessionQueryWork.Relation.DISPATCH);
    for (String name :
        List.of("selectReservedSlot", "selectTokenAmount", "lockProgressReservation"))
      r.put(
          ROOT + "budget.mapper.ReservationMapper." + name, SessionQueryWork.Relation.RESERVATION);
    r.put(
        ROOT + "session.mapper.ScopeQueryReservationReadMapper.selectMaps",
        SessionQueryWork.Relation.RESERVATION);
    r.put(
        ROOT + "execution.mapper.TaskAttemptMapper.lockProgressAttempt",
        SessionQueryWork.Relation.ATTEMPT);
    return Map.copyOf(r);
  }
}
