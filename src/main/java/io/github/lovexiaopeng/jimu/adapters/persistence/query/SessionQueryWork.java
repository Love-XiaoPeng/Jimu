package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.LinkedHashMap;
import java.util.Map;

/** 由真实事务持有的证明和终算；借用者不能提交，完成后所有引用失效。 */
public final class SessionQueryWork {
  private final SessionQueryContext context;
  private final Object token = new Object();
  private final RuntimeException metadataClosed;
  private final MetadataError metadataError;

  private static final class MetadataClosedFailure extends IllegalStateException {
    MetadataClosedFailure() {
      super("Closed or foreign metadata batch");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  private static final class MetadataError extends RuntimeException {
    private Error error;

    MetadataError() {
      super("Metadata length error", null, false, false);
    }

    @Override
    public synchronized Throwable getCause() {
      return error;
    }
  }

  RuntimeException recordMetadataError(Error failure) {
    var first = context.recordedFailure();
    if (first != null) return first;
    metadataError.error = failure;
    return recordMetadataFailure(metadataError);
  }

  RuntimeException metadataClosedFailure() {
    var first = context.recordedFailure();
    return first == null ? metadataClosed : first;
  }

  private record Dirty(boolean closing, java.util.function.Consumer<Boolean> action) {}

  private final Map<String, Dirty> accounts = new LinkedHashMap<>();
  private final java.util.Map<Object, java.util.function.Consumer<java.util.Set<Dependency>>>
      invalidators = new java.util.IdentityHashMap<>();

  public void invalidator(
      Object key, java.util.function.Consumer<java.util.Set<Dependency>> invalidate) {
    active();
    invalidators.put(key, invalidate);
  }

  public void removeInvalidator(Object key) {
    active();
    invalidators.remove(key);
  }

  private final java.util.Set<String> locks = new java.util.HashSet<>();
  private final Object scopeScratch = new Object(), graphScratch = new Object();
  private String fenceKey, leaseKey, stepKey, responseKey;
  private long dependencyGeneration;
  private SessionCheckedHistories histories;
  private final Object projectionScratch = new Object();
  private final Object eventHistoryScratch = new Object();
  private SessionEventHistoryRows.Slot eventHistoryLength;

  private static final Object HISTORY_HOLDER = new Object();
  private SessionHistoryRows.Slot historyLength;
  private int historyLoads;

  SessionHistoryRows.Slot beginHistory(String tenant, String task) {
    active();
    context.checkHealthy();
    context.operation(12); // capacity/pending, reserve, allocation, link, increment and handoff
    if (historyLoads == 4) return null;
    if (historyLength != null && historyLength.pending())
      throw poisonProof(new IllegalStateException("History length already in flight"));
    requireProjectionLocks(tenant, task);
    context.retainScratch(HISTORY_HOLDER, 64); // new scratch-key reference, before allocation
    Object scratch = null;
    SessionHistoryRows.Slot next = null;
    boolean transferred = false;
    try {
      scratch = new Object();
      context.retainScratch(scratch, 4112);
      // 原4096及两条不可变statement别名16字节在Slot分配前保留；两字段清零及清引用先付。
      context.operation(68);
      next = new SessionHistoryRows.Slot(this, context, scratch, tenant, task);
      next.previous = historyLength;
      historyLength = next;
      historyLoads++;
      transferred = true;
      return next;
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    } finally {
      if (!transferred) {
        if (next != null) next.clear();
        next = null;
        if (scratch != null) context.releaseScratch(scratch);
      }
      scratch = null;
      context.releaseScratch(HISTORY_HOLDER);
    }
  }

  SessionHistoryRows.Slot pendingHistory() {
    context.operation(1);
    return historyLength != null && historyLength.pending() ? historyLength : null;
  }

  void invalidateHistoryLength() {
    context.operation(2);
    if (historyLength != null) historyLength.invalidate();
  }

  SessionEventHistoryRows.Slot beginEventHistory(String tenant, String task) {
    active();
    context.checkHealthy();
    context.operation(1);
    if (eventHistoryLength != null) return null; // 固定一个槽；已有raw仍驻留时回原独立路径。
    requireProjectionLocks(tenant, task);
    context.retainScratch(eventHistoryScratch, 4096);
    eventHistoryLength =
        new SessionEventHistoryRows.Slot(this, context, eventHistoryScratch, tenant, task);
    return eventHistoryLength;
  }

  boolean eventHistoryPending() {
    context.operation(1);
    return eventHistoryLength != null && eventHistoryLength.pending();
  }

  SessionEventHistoryRows.Slot eventHistoryLength(String statement) {
    context.operation(2);
    return eventHistoryLength != null && eventHistoryLength.accepts(statement)
        ? eventHistoryLength
        : null;
  }

  void invalidateEventHistoryLength() {
    if (eventHistoryLength != null) eventHistoryLength.invalidate();
  }

  public enum ProjectionRole {
    LINEAGE_SCOPE,
    ACCEPTED_EVENT,
    ACCEPTED_VIEW
  }

  private Projection[] projections;
  private String projectionTenant, projectionTask;

  /** 固定三role，不扩成任意行缓存；完整锁与同caller在物化前核验。 */
  public Projection reserveProjection(ProjectionRole role, String tenant, String task, long bytes) {
    try {
      active();
      context.checkHealthy();
      requireProjectionLocks(tenant, task);
      context.operation(4L + tenant.length() + task.length());
      if (projectionTenant != null
          && (!projectionTenant.equals(tenant) || !projectionTask.equals(task)))
        throw new IllegalStateException("Different projection caller");
      if (projections != null && projections[role.ordinal()] != null)
        throw new IllegalStateException("Projection role already held");
      context.retainScratch(projectionScratch, 1024);
      if (projections == null) projections = new Projection[3];
      Object rowOwner = new Object();
      context.retainScratch(rowOwner, bytes);
      var projection = new Projection(rowOwner, tenant, task);
      projections[role.ordinal()] = projection;
      projectionTenant = tenant;
      projectionTask = task;
      return projection;
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    }
  }

  public void requireProjectionLocks(String tenant, String task) {
    active();
    context.checkHealthy();
    context.operation(1L + tenant.length() + task.length());
    if (!locks.contains(tenant + ":" + task))
      throw poisonProof(new IllegalStateException("Task locks required before projection"));
    for (var source : stagedSources.values()) {
      context.operation(1L + source.caller().tenant().length() + source.caller().id().length());
      if (!locks.contains(source.caller().tenant() + ":" + source.caller().id()))
        throw poisonProof(new IllegalStateException("Full sorted task locks required"));
    }
  }

  public final class Projection {
    private final Object owner;
    private String tenant, task;
    private Map<String, Object> row;

    private Projection(Object owner, String tenant, String task) {
      this.owner = owner;
      this.tenant = tenant;
      this.task = task;
    }

    public Map<String, Object> loaded(Map<String, Object> value) {
      try {
        active();
        context.checkHealthy();
        context.operation(3L + tenant.length() + task.length());
        if (row != null || !tenant.equals(value.get("tenant_id")) || !task.equals(value.get("id")))
          throw new IllegalStateException("Different projection row");
        row = value;
        return row;
      } catch (RuntimeException failure) {
        throw poisonProof(failure);
      }
    }

    private void clear() {
      // 所有live/account/proof闭包先解除；原Map的文本引用随后消失，再释放本槽。
      if (row != null) row.clear();
      row = null;
      tenant = null;
      task = null;
      context.releaseScratch(owner);
    }
  }

  /** 仅供真实history service成功后登记；调用者已持有原history锁。 */
  public void checkedHistory(
      SessionScopeRecords.Caller identity,
      long version,
      String binding,
      io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs.HistoryRow row,
      java.time.LocalDateTime retain) {
    active();
    context.checkHealthy();
    if (!locks.contains(identity.tenant() + ":" + identity.id())) return;
    if (histories == null) histories = new SessionCheckedHistories(context, token);
    histories.remember(identity, version, binding, row, retain);
  }

  /** 只复用已发布且必经live/fence的caller证明；不返回任何动态history字段。 */
  public boolean checkedHistory(SessionScopeRecords.Caller identity, long version, String binding) {
    active();
    context.checkHealthy();
    if (proofFailure != null) throw proofFailure;
    return histories != null
        && scope != null
        && live != null
        && fence != null
        && caller != null
        && relationEqual(caller.tenant(), identity.tenant())
        && relationEqual(caller.id(), identity.id())
        && histories.checked(identity, version, binding);
  }

  private record CheckedCreated(
      Object token,
      String tenant,
      String caller,
      String lease,
      String response,
      String fence,
      long generation,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored stored,
      VerifiedGraph graph) {}

  private CheckedCreated created;
  private final Object createdScratch = new Object();

  public void checkedCreated(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored stored,
      VerifiedGraph graph) {
    active();
    long bytes = 512 + 2L * (stored.genesis().length() + stored.graph().length());
    context.retainScratch(createdScratch, bytes);
    created =
        new CheckedCreated(
            token,
            tenant,
            caller,
            leaseKey,
            responseKey,
            fenceKey,
            dependencyGeneration,
            stored,
            graph);
  }

  public boolean checkedCreated(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored stored) {
    active();
    return created != null
        && created.token() == token
        && created.tenant().equals(tenant)
        && created.caller().equals(caller)
        && created.stored() == stored
        && created.generation() == dependencyGeneration
        && java.util.Objects.equals(created.lease(), leaseKey)
        && java.util.Objects.equals(created.response(), responseKey)
        && java.util.Objects.equals(created.fence(), fenceKey);
  }

  public void releaseCreated() {
    created = null;
    context.releaseScratch(createdScratch);
  }

  public void bind(String lease, String step, String response) {
    active();
    if (leaseKey != null && !relationEqual(leaseKey, lease)
        || step != null && stepKey != null && !relationEqual(stepKey, step)
        || response != null && responseKey != null && !relationEqual(responseKey, response))
      throw new IllegalStateException("Different lease, step or response in session transaction");
    leaseKey = lease;
    if (step != null) stepKey = step;
    if (response != null) responseKey = response;
  }

  private boolean admission;
  private SessionScopeRecords.Caller stagingCaller;
  private final Map<String, SessionScopeRecords.SourceFact> stagedSources = new LinkedHashMap<>();
  private VerifiedGraph stagedGraph;
  private String stagedGraphKey, stagedGraphTenant;
  private final Object stagingScratch = new Object();

  public void admission() {
    active();
    admission = true;
  }

  public SessionScopeRecords.SourceFact stagedSource(SessionScopeRecords.Caller caller, String id) {
    active();
    return admission && caller.equals(stagingCaller) ? stagedSources.get(id) : null;
  }

  private SessionScopeRecords.SourceFact lineageSource;
  private SessionScopeRecords.Caller lineageCaller;
  private SessionScopeRecords.Validated lineageScope;
  private boolean lineageInputs, lineageIssued, lineageNode;
  private final Object lineageScopeScratch = new Object();

  /** 在调用方构造同步verify闭包前，先预留其捕获引用及借用字段。 */
  public void prepareLineageScope() {
    active();
    context.checkHealthy();
    context.operation(4);
    context.retainScratch(lineageScopeScratch, 512);
  }

  /** 原require已完整验证且holdScope的proof；仅同步借引用，不发布scope或免除live。 */
  public <T> T withLineageScope(
      SessionQueryContext query,
      SessionScopeRecords.Caller key,
      SessionScopeRecords.Validated proof,
      java.util.function.Supplier<T> verify) {
    boolean borrowed = false;
    try {
      active();
      context.checkHealthy();
      context.operation(18L + key.tenant().length() + key.id().length());
      if (query != context || SessionQueryTransaction.metadataOwner(query) != this)
        throw new IllegalStateException("Foreign lineage scope owner");
      context.checkHealthy();
      if (scope != null || lineageScope != null || lineageInputs)
        throw new IllegalStateException("Lineage scope already borrowed or published");
      requireProjectionLocks(key.tenant(), key.id());
      for (var source : proof.sources()) {
        context.operation(2L + source.tenant().length() + source.id().length());
        if (!key.tenant().equals(source.tenant())
            || !locks.contains(source.tenant() + ":" + source.id()))
          throw new IllegalStateException("Full sorted source locks required");
      }
      context.retainScratch(lineageScopeScratch, 512);
      lineageCaller = key;
      lineageScope = proof;
      borrowed = true;
      return verify.get();
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    } catch (Error failure) {
      recordMetadataError(failure);
      throw failure;
    } finally {
      if (borrowed) {
        lineageCaller = null;
        lineageScope = null;
        context.releaseScratch(lineageScopeScratch);
      }
    }
  }

  public void beginLineageInputs() {
    active();
    context.checkHealthy();
    context.operation(3);
    if (lineageInputs) throw poisonProof(new IllegalStateException("Nested lineage inputs"));
    lineageInputs = true;
    lineageIssued = false;
  }

  public void endLineageInputs() {
    lineageInputs = false;
    lineageIssued = false;
  }

  /** 仅本次query/连接/锁中的已核结构；包装由借用owner先行预留。 */
  public record LineageBasis(
      SessionScopeRecords.Caller caller,
      SessionScopeRecords.Scope scope,
      SessionScopeRecords.Authority authority,
      String binding) {}

  public LineageBasis borrowLineageNode(SessionQueryContext query, String tenant, String id) {
    try {
      var source = borrowLineageSource(query, tenant, id);
      if (source != null) {
        lineageNode = true;
        lineageIssued = true;
        return new LineageBasis(
            source.caller(), source.scope(), source.authority(), source.history().binding());
      }
      if (lineageScope == null || !tenant.equals(lineageCaller.tenant())) return null;
      context.operation(8L + id.length());
      SessionScopeRecords.Caller node = null;
      SessionScopeRecords.Scope structure = null;
      SessionScopeRecords.Authority authority = null;
      if (id.equals(lineageCaller.id())) {
        node = lineageCaller;
        structure = lineageScope.scope();
        authority = lineageScope.authority();
      } else {
        for (var candidate : lineageScope.sources()) {
          context.operation(2L + id.length());
          if (id.equals(candidate.id())) {
            node = candidate;
            break;
          }
        }
        context.operation(4L + id.length());
        structure = lineageScope.sourceScopes().get(id);
        if (structure != null) {
          context.operation(
              12L
                  + tenant.length()
                  + id.length()
                  + structure.raw().length()
                  + structure.digest().length());
          authority = validatedAuthority(tenant, id, structure.raw(), structure.digest());
        }
      }
      if (node == null
          || structure == null
          || authority == null
          || structure.recordVersion() != 1
          || structure.authorityVersion() != 1) return null;
      context.operation(
          8L
              + tenant.length()
              + id.length()
              + io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
                  .length());
      if (!tenant.equals(node.tenant())
          || !id.equals(node.id())
          || !io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
              .equals(node.capabilities())) return null;
      requireProjectionLocks(tenant, id);
      context.retainScratch(token, 512);
      lineageNode = true;
      lineageIssued = true;
      return new LineageBasis(node, structure, authority, authority.binding());
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    } catch (Error failure) {
      recordMetadataError(failure);
      throw failure;
    }
  }

  /** 同步Fact读取期间借原staging；不返回权限token，不允许借用中写入或发布后退款。 */
  public SessionScopeRecords.SourceFact borrowLineageSource(
      SessionQueryContext query, String tenant, String id) {
    try {
      active();
      context.checkHealthy();
      context.operation(12L + tenant.length() + id.length());
      if (query != context || SessionQueryTransaction.metadataOwner(context) != this)
        throw new IllegalStateException("Foreign lineage source owner");
      context.checkHealthy();
      if (lineageSource != null) throw new IllegalStateException("Nested lineage source borrow");
      if (!admission
          || stagingCaller == null
          || scope != null
          || !tenant.equals(stagingCaller.tenant())
          || id.equals(stagingCaller.id())) return null;
      context.operation(4L + id.length());
      var fact = stagedSources.get(id);
      if (fact == null) return null;
      context.operation(
          10L
              + tenant.length()
              + id.length()
              + io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
                  .length());
      if (!tenant.equals(fact.caller().tenant())
          || !id.equals(fact.caller().id())
          || !io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
              .equals(fact.caller().capabilities())
          || fact.scope().recordVersion() != 1
          || fact.scope().authorityVersion() != 1) return null;
      requireProjectionLocks(tenant, stagingCaller.id());
      // token仅作本Work已有的scratch key；512覆盖借用字段/栈别名和失败包装，先付清理动作。
      context.operation(12);
      context.retainScratch(token, 512);
      lineageSource = fact;
      return fact;
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    }
  }

  public void releaseLineageSource() {
    lineageSource = null;
    lineageNode = false;
    context.releaseScratch(token);
  }

  boolean lineageSourceActive() {
    return lineageSource != null || lineageNode;
  }

  /** Error仍原样抛出，记录cause以禁止被上层捕获后提交。 */
  public void poisonLineageSource(Error failure) {
    recordMetadataError(failure);
  }

  public void stageSource(SessionScopeRecords.Caller caller, SessionScopeRecords.SourceFact fact) {
    active();
    if (!admission) return;
    if (stagingCaller != null && !stagingCaller.equals(caller))
      throw new IllegalStateException("Different admission caller");
    stagingCaller = caller;
    long bytes = 0;
    for (var f : stagedSources.values())
      if (!f.caller().id().equals(fact.caller().id())) bytes = Math.addExact(bytes, sourceBytes(f));
    bytes = Math.addExact(bytes, sourceBytes(fact));
    context.retainScratch(stagingScratch, bytes);
    stagedSources.put(fact.caller().id(), fact);
  }

  private static long sourceBytes(SessionScopeRecords.SourceFact fact) {
    long bytes = fact.scope().bytes();
    for (var t : fact.targets()) bytes = Math.addExact(bytes, t.bytes());
    return Math.addExact(Math.multiplyExact(3L, bytes), 2048);
  }

  public void stageGraph(String tenant, String id, VerifiedGraph proof) {
    active();
    if (lineageSource != null || lineageNode || lineageScope != null)
      throw poisonProof(new IllegalStateException("Stage during lineage structure borrow"));
    stagedGraphKey = tenant + ":" + id;
    stagedGraphTenant = tenant;
    holdGraph(proof);
    stagedGraph = proof;
  }

  public VerifiedGraph stagedGraph(String tenant, String id) {
    active();
    return (tenant + ":" + id).equals(stagedGraphKey) ? stagedGraph : null;
  }

  private record CheckedAuthority(
      String raw, String digest, SessionScopeRecords.Authority authority) {}

  private final Map<String, CheckedAuthority> authorities = new LinkedHashMap<>();

  /** 全tenant/task键；依赖含本行及authority中引用的全部来源，不以caller写豁免交叉依赖。 */
  public record Dependency(String tenant, String task, String actor, String plugin) {
    public Dependency(String tenant, String task) {
      this(tenant, task, null, null);
    }

    static Dependency actor(String tenant, String actor) {
      return new Dependency(tenant, null, actor, null);
    }

    static Dependency installation(String tenant, String plugin) {
      return new Dependency(tenant, null, null, plugin);
    }
  }

  private static java.util.Set<Dependency> dependencies(
      String tenant, String task, SessionScopeRecords.Authority value) {
    var keys = new java.util.HashSet<Dependency>();
    keys.add(new Dependency(tenant, task));
    keys.add(Dependency.actor(tenant, value.owner()));
    // 每个来源的冻结pin可能不同；tenant下任一安装变化保守覆盖，不能伪装task id。
    keys.add(Dependency.installation(tenant, "*"));
    for (var target : value.targets()) keys.add(new Dependency(tenant, target.id()));
    return keys;
  }

  private static boolean intersects(
      java.util.Set<Dependency> dependencies, java.util.Set<Dependency> writes) {
    return writes == null
        || dependencies.stream()
            .anyMatch(
                d ->
                    writes.stream()
                        .anyMatch(
                            w ->
                                d.tenant().equals(w.tenant())
                                    && (d.equals(w)
                                        || d.plugin() != null
                                            && w.plugin() != null
                                            && (d.plugin().equals("*")
                                                || w.plugin().equals("*")))));
  }

  public void validatedAuthority(
      SessionScopeRecords.Caller caller,
      SessionScopeRecords.Scope scope,
      SessionScopeRecords.Authority authority) {
    active();
    authorities.put(
        caller.tenant() + ":" + caller.id(),
        new CheckedAuthority(scope.raw(), scope.digest(), authority));
  }

  public SessionScopeRecords.Authority validatedAuthority(
      String tenant, String id, String raw, String digest) {
    active();
    var found = authorities.get(tenant + ":" + id);
    return found != null && found.raw().equals(raw) && found.digest().equals(digest)
        ? found.authority()
        : null;
  }

  private RuntimeException proofFailure;
  private com.fasterxml.jackson.databind.JsonNode authority;
  private final Object policyScratch = new Object();
  private String policyRaw, policyHash;

  /** 仅同Work的不可变本地策略串散列；不是数据库raw证明或当前授权，外来串仍独立计算。 */
  public String policyHash(String raw, java.util.function.Supplier<String> calculate) {
    active();
    context.checkHealthy();
    context.operation(1); // identity比较也计量；不以文本相同替代同一不可变对象。
    try {
      if (sameIdentity(policyRaw, raw) && policyHash != null) return policyHash;
      if (policyRaw != null) return calculate.get();
      context.retainScratch(policyScratch, 512L + 2L * (raw.length() + 64L));
      String value = calculate.get();
      policyRaw = raw;
      policyHash = value;
      return value;
    } catch (RuntimeException failure) {
      throw poisonProof(failure);
    }
  }

  private static boolean sameIdentity(Object left, Object right) {
    return left == right;
  }

  // Metadata cleanup must be able to register failure after completion without reviving proof.
  RuntimeException recordMetadataFailure(RuntimeException failure) {
    var first = context.recordFailure(failure);
    if (!completed && proofFailure == null) proofFailure = first;
    return first;
  }

  public RuntimeException poisonProof(RuntimeException failure) {
    active();
    var first = context.recordFailure(failure);
    if (proofFailure == null) proofFailure = first;
    return first;
  }

  public boolean lineage() {
    return context.lineage();
  }

  /** 仅未发布证明、未写正文的具名scope不可用允许状态脱敏；损坏或已有首错永不豁免。 */
  public boolean scopeStatus(TaskFailure failure) {
    context.checkHealthy();
    return !protectedWrite
        && scope == null
        && failure.status() == 404
        && io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode.SESSION_SCOPE_UNAVAILABLE.equals(
            failure.code());
  }

  public com.fasterxml.jackson.databind.JsonNode authority(
      java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> parse) {
    active();
    if (authority == null) authority = parse.get();
    return authority;
  }

  public void holdScope(SessionScopeRecords.Validated proof) {
    long bytes = proof.scope().bytes();
    for (var source : proof.sourceScopes().values()) bytes = Math.addExact(bytes, source.bytes());
    for (var target : proof.authority().targets())
      bytes = Math.addExact(bytes, SessionQueryContext.scalarBytes(target.canonical()));
    context.retainScratch(
        scopeScratch, Math.addExact(3L * bytes, 2048L * (proof.sources().size() + 1)));
  }

  public void locked(String tenant, String task) {
    active();
    locks.add(tenant + ":" + task);
  }

  public SessionScopeRecords.Validated proof() {
    active();
    return scope;
  }

  private SessionScopeRecords.Caller caller;
  private SessionScopeRecords.Validated scope;
  private VerifiedGraph graph;
  private Runnable live;
  private Runnable fence;
  private Runnable expiry;
  private long generation, sealedGeneration;
  private boolean sealed, completed;

  SessionQueryWork(SessionQueryContext context) {
    this.context = context;
    context.operation(12); // two stackless metadata failures, fields and failure handoff.
    context.retainScratch(relationScratch, RELATION_ROOT_BYTES);
    metadataClosed = new MetadataClosedFailure();
    metadataError = new MetadataError();
    relations = new LinkedHashMap<>();
    relationStrings = new java.util.IdentityHashMap<>();
  }

  boolean metadataIdentity(SessionQueryContext query, Object expectedToken) {
    return !completed && context == query && token == expectedToken;
  }

  boolean owns(SessionQueryContext query) {
    return context == query;
  }

  private Dependency leaseTask;

  enum Relation {
    STEP,
    INVOCATION,
    DISPATCH,
    ATTEMPT,
    RESERVATION
  }

  private record RelationKey(Relation type, String tenant, String id) {}

  private record Related(String task, String parent, String category) {}

  private Map<RelationKey, Related> relations;
  private java.util.IdentityHashMap<String, Integer> relationStrings;
  private final Object relationScratch = new Object();
  // Root/map table and lease dependency; entry includes key/value/node and amortized table growth.
  static final long RELATION_ROOT_BYTES = 1280,
      RELATION_ENTRY_BYTES = 256,
      RELATION_STRING_BYTES = 192;
  private long relationBytes = RELATION_ROOT_BYTES;
  private long relationTableBytes;
  private long relationStringTableBytes;
  private final Object lookupScratch = new Object(),
      extractionScratch = new Object(),
      dependencyScratch = new Object();

  void extraction(boolean begin) {
    if (begin) context.retainScratch(extractionScratch, 4096);
    else context.releaseScratch(extractionScratch);
  }

  void inspect(long operations) {
    context.operation(operations);
  }

  /** 仅由具名Mapper真实读回或成功INSERT登记；不从ID、provider或lease推断归属。 */
  void observed(
      Relation type, String tenant, String id, String task, String parent, String category) {
    active();
    if (tenant == null || id == null || task == null) return;
    // Map lookup/key hashing is separate from the value comparisons below.
    context.primitive("relation.lookup", 1L + tenant.length() + id.length(), () -> null);
    context.retainScratch(lookupScratch, 256);
    try {
      observeReserved(type, tenant, id, task, parent, category);
    } finally {
      // The helper's temporary key/value are gone; successful entries retain continuous ownership.
      context.releaseScratch(lookupScratch);
      context.retainScratch(relationScratch, relationBytes);
    }
  }

  private void observeReserved(
      Relation type, String tenant, String id, String task, String parent, String category) {
    var key = new RelationKey(type, tenant, id);
    var previous = relations.get(key);
    if (previous != null) {
      if (!relationEqual(previous.task(), task)
          || !relationEqual(previous.parent(), parent)
          || !relationEqual(previous.category(), category)) {
        var failure = new IllegalStateException("Session row relationship changed");
        poisonProof(failure);
        throw failure;
      }
      return;
    }
    var strings = new String[] {tenant, id, task, parent, category};
    long added = 0;
    int addedStrings = 0;
    for (int i = 0; i < strings.length; i++) {
      context.operation(7);
      if (unique(strings, i) && !relationStrings.containsKey(strings[i])) {
        added += RELATION_STRING_BYTES + 2L * strings[i].length();
        addedStrings++;
      }
    }
    long table = Math.max(relationTableBytes, 32L * (relations.size() + 1));
    // Both map backing arrays remain at their high-water capacity after entries are removed.
    long stringTable =
        Math.max(relationStringTableBytes, 64L * (relationStrings.size() + addedStrings));
    long next =
        Math.addExact(
            relationBytes,
            RELATION_ENTRY_BYTES
                + added
                + table
                - relationTableBytes
                + stringTable
                - relationStringTableBytes);
    // Reserve the bounded update work before publishing either map; a limit rejection cannot
    // leave the string reference counts partly updated or roll the live owner back to its old size.
    // 35 bounds five unique scans (<=10 identity comparisons), five null tests,
    // five branches and <=15 IdentityHashMap get/put actions. The entry Map put/hash
    // is an additional operation, never paid by the preceding lookup.
    context.operation(35L + 1 + tenant.length() + id.length());
    context.retainScratch(relationScratch, next);
    // Move the already allocated lookup key into the entry's ownership without charging it twice.
    context.releaseScratch(lookupScratch);
    relations.put(key, new Related(task, parent, category));
    for (int i = 0; i < strings.length; i++)
      if (unique(strings, i)) {
        relationStrings.put(strings[i], relationStrings.getOrDefault(strings[i], 0) + 1);
      }
    relationTableBytes = table;
    relationStringTableBytes = stringTable;
    relationBytes = next;
  }

  private static boolean unique(String[] strings, int i) {
    if (strings[i] == null) return false;
    for (int j = 0; j < i; j++) if (strings[j] == strings[i]) return false;
    return true;
  }

  private boolean relationEqual(String a, String b) {
    if (a == b) {
      context.operation(1); // identity comparison, no content traversal
      return true;
    }
    if (a == null || b == null) {
      context.operation(1);
      return false;
    }
    return context.primitive(
        "relation.compare", Math.max(a.length(), b.length()), () -> a.equals(b));
  }

  // Returns a borrowed scalar, never an unowned Dependency/Set escaping the lookup workspace.
  String related(
      Relation type,
      String tenant,
      String id,
      String expectedTask,
      String expectedParent,
      String category) {
    active();
    if (tenant == null || id == null) return null;
    context.retainScratch(lookupScratch, 256);
    try {
      return relatedReserved(type, tenant, id, expectedTask, expectedParent, category);
    } finally {
      context.releaseScratch(lookupScratch);
    }
  }

  private String relatedReserved(
      Relation type,
      String tenant,
      String id,
      String expectedTask,
      String expectedParent,
      String category) {
    context.primitive("relation.lookup", 1L + tenant.length() + id.length(), () -> null);
    var value = relations.get(new RelationKey(type, tenant, id));
    if (value == null
        || expectedTask != null && !relationEqual(expectedTask, value.task())
        || expectedParent != null && !relationEqual(expectedParent, value.parent())
        || category != null && !relationEqual(category, value.category())) return null;
    if (type == Relation.STEP && stepKey != null && !relationEqual(stepKey, id)
        || type == Relation.DISPATCH && responseKey != null && !relationEqual(responseKey, id)
        || (type == Relation.INVOCATION || type == Relation.DISPATCH)
            && stepKey != null
            && !relationEqual(stepKey, value.parent())) return null;
    if (type == Relation.RESERVATION
        && category != null
        && context.primitive("relation.prefix", 5, () -> category.startsWith("MODEL"))
        && responseKey != null
        && !relationEqual(responseKey, value.parent())) return null;
    if (type == Relation.RESERVATION
        && relationEqual("TOOL", category)
        && relatedReserved(Relation.INVOCATION, tenant, id, value.task(), null, null) == null)
      return null;
    if (type == Relation.RESERVATION
        && category == null
        && !relationEqual("QUEUE", value.category())
        && !relationEqual("RUNNING", value.category())
        && !relationEqual("QUESTION_TASK", value.category())) return null;
    return value.task();
  }

  public void leaseTask(String tenant, String task) {
    var next = new Dependency(tenant, task);
    if (leaseTask != null
        && (!relationEqual(leaseTask.tenant(), tenant) || !relationEqual(leaseTask.task(), task)))
      throw new IllegalStateException("Different lease task");
    leaseTask = next;
  }

  boolean callerProgress(java.util.Set<Dependency> keys) {
    if (keys == null || keys.size() != 1) return false;
    var key = keys.iterator().next();
    return caller != null
        ? relationEqual(key.tenant(), caller.tenant())
            && relationEqual(key.task(), caller.id())
            && key.actor() == null
            && key.plugin() == null
        : leaseTask != null
            && relationEqual(key.tenant(), leaseTask.tenant())
            && relationEqual(key.task(), leaseTask.task())
            && key.actor() == null
            && key.plugin() == null;
  }

  public Object token() {
    active();
    return token;
  }

  private void active() {
    if (completed) throw new IllegalStateException("Completed session transaction");
  }

  public void mutation() {
    active();
    invalidateEventHistoryLength();
    invalidateHistoryLength();
    if (sealed) throw new IllegalStateException("Session write after seal");
    generation = Math.incrementExact(generation);
  }

  public void dependencyMutation() {
    dependencyMutation(null);
  }

  public void dependencyMutation(java.util.Set<Dependency> writes) {
    context.retainScratch(dependencyScratch, 4096);
    try {
      mutateDependencies(writes);
    } catch (RuntimeException failure) {
      poisonProof(failure);
      throw failure;
    } finally {
      context.releaseScratch(dependencyScratch);
    }
  }

  private void mutateDependencies(java.util.Set<Dependency> writes) {
    context.operation(1);
    if (lineageSource != null || lineageNode || lineageScope != null)
      throw poisonProof(new IllegalStateException("Write during lineage structure borrow"));
    if (lineageInputs && lineageIssued)
      for (var fact : stagedSources.values()) {
        if (lineageInputChanged(fact, writes))
          throw poisonProof(new IllegalStateException("Write while lineage inputs remain live"));
      }
    mutation();
    if (histories != null) histories.invalidate(writes);
    // Invalidate before SQL, including staging/unknown writes; fresh SELECT/INSERT must reprove.
    invalidateRelations(writes);
    context.retainScratch(relationScratch, relationBytes);
    dependencyGeneration = Math.incrementExact(dependencyGeneration);
    releaseCreated();
    for (var invalidate : invalidators.values()) invalidate.accept(writes);
    invalidateProof(writes);
  }

  /** 输入仍在Service局部Map中：直接比较已有引用，新增遍历及字符比较逐项先付。 */
  private boolean lineageInputChanged(
      SessionScopeRecords.SourceFact fact, java.util.Set<Dependency> writes) {
    context.operation(3);
    if (writes == null) return true;
    for (var write : writes) {
      context.operation(6L + Math.max(fact.caller().tenant().length(), write.tenant().length()));
      if (!fact.caller().tenant().equals(write.tenant())) continue;
      if (write.task() == null) {
        context.operation(3);
        if (write.plugin() != null || write.actor() == null) return true;
        context.operation(2L + Math.max(fact.authority().owner().length(), write.actor().length()));
        if (fact.authority().owner().equals(write.actor())) return true;
      } else {
        context.operation(3L + Math.max(fact.caller().id().length(), write.task().length()));
        if (fact.caller().id().equals(write.task())) return true;
        for (var target : fact.authority().targets()) {
          context.operation(3L + Math.max(target.id().length(), write.task().length()));
          if (target.id().equals(write.task())) return true;
        }
      }
    }
    return false;
  }

  private void invalidateRelations(java.util.Set<Dependency> writes) {
    Object planOwner = new Object();
    context.retainScratch(planOwner, 512L + 64L * relations.size());
    try {
      invalidateRelationsReserved(writes);
    } finally {
      context.releaseScratch(planOwner);
    }
  }

  private void invalidateRelationsReserved(java.util.Set<Dependency> writes) {
    var removals = new java.util.ArrayList<RelationKey>(relations.size());
    long updateWork = 0;
    for (var entry : relations.entrySet()) {
      context.operation(1);
      boolean remove = writes == null;
      if (writes != null)
        for (var write : writes) {
          context.operation(1);
          if (relationEqual(entry.getKey().tenant(), write.tenant())
              && (write.task() == null || relationEqual(entry.getValue().task(), write.task())))
            remove = true;
        }
      if (remove) {
        context.operation(1); // plan append
        removals.add(entry.getKey());
        // Key Map get/remove (including key hash), iteration and the five identity
        // count updates/unique tests. Reserve ALL mutations before touching either Map.
        updateWork += 40L + 2L * (entry.getKey().tenant().length() + entry.getKey().id().length());
      }
    }
    context.operation(updateWork);
    for (var key : removals) {
      var related = relations.get(key);
      var strings =
          new String[] {
            key.tenant(), key.id(), related.task(), related.parent(), related.category()
          };
      for (int i = 0; i < strings.length; i++)
        if (unique(strings, i)) {
          int count = relationStrings.get(strings[i]);
          if (count == 1) {
            relationStrings.remove(strings[i]);
            relationBytes -= RELATION_STRING_BYTES + 2L * strings[i].length();
          } else relationStrings.put(strings[i], count - 1);
        }
      relations.remove(key);
      relationBytes -= RELATION_ENTRY_BYTES;
    }
  }

  private void invalidateProof(java.util.Set<Dependency> writes) {
    if (scope != null
        && intersects(dependencies(caller.tenant(), caller.id(), scope.authority()), writes)) {
      var failure = new IllegalStateException("Session proof dependency changed after publication");
      poisonProof(failure);
      throw failure;
    }
    stagedSources
        .values()
        .removeIf(
            fact ->
                intersects(
                    dependencies(fact.caller().tenant(), fact.caller().id(), fact.authority()),
                    writes));
    authorities
        .entrySet()
        .removeIf(
            entry ->
                intersects(
                    dependencies(
                        entry.getValue().authority().tenant(),
                        entry.getValue().authority().caller(),
                        entry.getValue().authority()),
                    writes));
    if (stagedGraph != null
        && (writes == null
            || stagedGraph.nodes().keySet().stream()
                .anyMatch(id -> writes.contains(new Dependency(stagedGraphTenant, id)))
            || writes != null
                && writes.stream()
                    .anyMatch(w -> w.tenant().equals(stagedGraphTenant) && w.task() == null))) {
      stagedGraph = null;
      stagedGraphKey = null;
      stagedGraphTenant = null;
      context.releaseScratch(graphScratch);
    }
    long bytes = 0;
    for (var fact : stagedSources.values()) bytes = Math.addExact(bytes, sourceBytes(fact));
    context.retainScratch(stagingScratch, bytes);
    // 该树没有逐键证明；任何相关依赖写后必须重新解析，不能从旧Map回填。
    authority = null;
  }

  private boolean protectedWrite;

  public void protectedWrite() {
    active();
    protectedWrite = true;
  }

  public void settlement() {
    active();
    if (proofFailure != null) throw proofFailure;
    if (protectedWrite)
      throw new IllegalStateException("Cannot settle a partially written response");
    // 仅供写响应/新槽之前的白名单拒绝；不可挪用于finalizer或正文写后失败。
    live = null;
    expiry = null;
  }

  public void account(String key, boolean closing, java.util.function.Consumer<Boolean> action) {
    mutation();
    var prior = accounts.get(key);
    accounts.put(key, new Dirty(closing || prior != null && prior.closing(), action));
  }

  public SessionScopeRecords.Validated scope(SessionScopeRecords.Caller key) {
    active();
    if (caller == null) return null;
    if (!caller.tenant().equals(key.tenant())
        || !caller.id().equals(key.id())
        || !caller.owner().equals(key.owner())
        || !caller.tools().equals(key.tools())
        || caller.epoch() != key.epoch()
        || !caller.requestDigest().equals(key.requestDigest()))
      throw new IllegalStateException("Different caller in session transaction");
    return scope;
  }

  public void scope(
      SessionScopeRecords.Caller key, SessionScopeRecords.Validated proof, Runnable terminal) {
    active();
    if (scope != null) throw new IllegalStateException("Session proof already published");
    if (lineageScope != null || lineageSource != null || lineageNode)
      throw poisonProof(
          new IllegalStateException("Scope publication during lineage structure borrow"));
    if (!locks.contains(key.tenant() + ":" + key.id())
        || proof.sources().stream().anyMatch(s -> !locks.contains(s.tenant() + ":" + s.id())))
      throw new IllegalStateException("Full sorted task locks required before proof publication");
    caller = key;
    scope = proof;
    live = terminal;
    holdScope(proof);
  }

  public VerifiedGraph graph(String tenant, String id) {
    active();
    return caller != null && caller.tenant().equals(tenant) && caller.id().equals(id)
        ? graph
        : null;
  }

  public void graph(String tenant, String id, VerifiedGraph proof) {
    active();
    context.operation(1);
    if (lineageSource != null || lineageNode)
      throw poisonProof(new IllegalStateException("Publish during lineage source borrow"));
    if (lineageScope != null
        && lineageCaller.tenant().equals(tenant)
        && lineageCaller.id().equals(id)) {
      holdGraph(proof); // 返回graph先由原owner承接，再释放输入，最后由require发布。
      return;
    }
    if (caller == null && lineageInputs) {
      holdGraph(proof);
      return;
    }
    if (caller == null || !caller.tenant().equals(tenant) || !caller.id().equals(id)) return;
    holdGraph(proof);
    graph = proof;
    stagedSources.clear();
    stagedGraph = null;
    stagedGraphKey = null;
    stagedGraphTenant = null;
    stagingCaller = null;
    context.releaseScratch(stagingScratch);
  }

  private void holdGraph(VerifiedGraph proof) {
    long bytes =
        3072
            + io.github.lovexiaopeng.jimu.kernel.session.domain.SessionLineageGraph
                .indexWorkspaceBytes(proof.genesis().size());
    for (var node : proof.nodes().values())
      bytes = Math.addExact(bytes, node.genesisSnapshot().length() * 3L + 2048);
    context.retainScratch(graphScratch, bytes);
  }

  public void fence(String key, Runnable action) {
    active();
    if (fenceKey != null && !fenceKey.equals(key))
      throw new IllegalStateException("Different fence in session transaction");
    fenceKey = key;
    fence = action;
  }

  public void expiry(Runnable action) {
    active();
    expiry = action;
  }

  public void checkLiveDeadline() {
    active();
    if (expiry != null) expiry.run();
  }

  void finish() {
    active();
    context.checkHealthy();
    if (proofFailure != null) throw proofFailure;
    for (var account : accounts.values()) account.action().accept(account.closing());
    if (live != null) live.run();
    checkLiveDeadline();
    if (fence != null) fence.run();
    sealedGeneration = generation;
    sealed = true;
    beforeCommit();
  }

  void beforeCommit() {
    active();
    context.checkHealthy();
    if (proofFailure != null) throw proofFailure;
    if (!sealed || generation != sealedGeneration)
      throw new IllegalStateException("Unsealed session transaction");
    if (expiry != null) expiry.run();
    context.checkHealthy();
  }

  void complete() {
    releaseLineageSource();
    lineageScope = null;
    lineageCaller = null;
    endLineageInputs();
    context.releaseScratch(lineageScopeScratch);
    policyRaw = null;
    policyHash = null;
    context.releaseScratch(policyScratch);
    if (histories != null) histories.clear();
    histories = null;
    releaseCreated();
    accounts.clear();
    invalidators.clear();
    locks.clear();
    caller = null;
    scope = null;
    graph = null;
    authority = null;
    proofFailure = null;
    live = null;
    fence = null;
    expiry = null;
    authorities.clear();
    stagedSources.clear();
    stagedGraph = null;
    stagedGraphKey = null;
    stagedGraphTenant = null;
    stagingCaller = null;
    fenceKey = null;
    leaseKey = null;
    stepKey = null;
    responseKey = null;
    leaseTask = null;
    relations.clear();
    relations = null;
    relationStrings.clear();
    relationStrings = null;
    relationBytes = 0;
    context.releaseScratch(relationScratch);
    context.releaseScratch(stagingScratch);
    if (eventHistoryLength != null) eventHistoryLength.clear();
    eventHistoryLength = null;
    if (historyLength != null) historyLength.clear();
    historyLength = null;
    completed = true;
    context.releaseScratch(scopeScratch);
    context.releaseScratch(graphScratch);
    if (projections != null) {
      for (var projection : projections) if (projection != null) projection.clear();
      java.util.Arrays.fill(projections, null);
      projections = null;
    }
    projectionTenant = null;
    projectionTask = null;
    context.releaseScratch(projectionScratch);
  }
}
