package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph;
import java.util.LinkedHashMap;
import java.util.Map;

/** 由真实事务持有的证明和终算；借用者不能提交，完成后所有引用失效。 */
public final class SessionQueryWork {
  private final SessionQueryContext context;
  private final Object token = new Object();

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
    if (leaseKey != null && !leaseKey.equals(lease)
        || step != null && stepKey != null && !stepKey.equals(step)
        || response != null && responseKey != null && !responseKey.equals(response))
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

  public void poisonProof(RuntimeException failure) {
    active();
    if (proofFailure == null) proofFailure = failure;
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
    context.retainScratch(relationScratch, RELATION_ROOT_BYTES);
    relations = new LinkedHashMap<>();
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
  private final Object relationScratch = new Object();
  // Root/map table and lease dependency; entry includes key/value/node and amortized table growth.
  static final long RELATION_ROOT_BYTES = 256, RELATION_ENTRY_BYTES = 256;
  private long relationBytes = RELATION_ROOT_BYTES;
  private long relationTableBytes;
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
    context.operation(
        1L
            + tenant.length()
            + id.length()
            + task.length()
            + (parent == null ? 0 : parent.length())
            + (category == null ? 0 : category.length()));
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
      if (!java.util.Objects.equals(previous.task(), task)
          || !java.util.Objects.equals(previous.parent(), parent)
          || !java.util.Objects.equals(previous.category(), category)) {
        var failure = new IllegalStateException("Session row relationship changed");
        poisonProof(failure);
        throw failure;
      }
      return;
    }
    long table = Math.max(relationTableBytes, 32L * (relations.size() + 1));
    long next =
        Math.addExact(
            relationBytes,
            relationEntryBytes(tenant, id, task, parent, category) + table - relationTableBytes);
    context.retainScratch(relationScratch, next);
    // Move the already allocated lookup key into the entry's ownership without charging it twice.
    context.releaseScratch(lookupScratch);
    relations.put(key, new Related(task, parent, category));
    relationTableBytes = table;
    relationBytes = next;
  }

  private static long relationEntryBytes(
      String tenant, String id, String task, String parent, String category) {
    // Retain borrowed row strings after ResultSet closes; temporary keys reuse those scalars.
    return RELATION_ENTRY_BYTES
        + 5L * 64
        + 2L
            * (tenant.length()
                + id.length()
                + task.length()
                + (parent == null ? 0 : parent.length())
                + (category == null ? 0 : category.length()));
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
    context.operation(1L + tenant.length() + id.length());
    var value = relations.get(new RelationKey(type, tenant, id));
    if (value == null
        || expectedTask != null && !expectedTask.equals(value.task())
        || expectedParent != null && !expectedParent.equals(value.parent())
        || category != null && !category.equals(value.category())) return null;
    if (type == Relation.STEP && stepKey != null && !stepKey.equals(id)
        || type == Relation.DISPATCH && responseKey != null && !responseKey.equals(id)
        || (type == Relation.INVOCATION || type == Relation.DISPATCH)
            && stepKey != null
            && !stepKey.equals(value.parent())) return null;
    if (type == Relation.RESERVATION
        && category != null
        && category.startsWith("MODEL")
        && responseKey != null
        && !responseKey.equals(value.parent())) return null;
    if (type == Relation.RESERVATION
        && "TOOL".equals(category)
        && relatedReserved(Relation.INVOCATION, tenant, id, value.task(), null, null) == null)
      return null;
    if (type == Relation.RESERVATION
        && category == null
        && !java.util.List.of("QUEUE", "RUNNING", "QUESTION_TASK").contains(value.category()))
      return null;
    return value.task();
  }

  public void leaseTask(String tenant, String task) {
    var next = new Dependency(tenant, task);
    if (leaseTask != null && !leaseTask.equals(next))
      throw new IllegalStateException("Different lease task");
    leaseTask = next;
  }

  boolean callerProgress(java.util.Set<Dependency> keys) {
    if (keys == null || keys.size() != 1) return false;
    var key = keys.iterator().next();
    return caller != null
        ? java.util.Objects.equals(key.tenant(), caller.tenant())
            && java.util.Objects.equals(key.task(), caller.id())
            && key.actor() == null
            && key.plugin() == null
        : key.equals(leaseTask);
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
    } finally {
      context.releaseScratch(dependencyScratch);
    }
  }

  private void mutateDependencies(java.util.Set<Dependency> writes) {
    mutation();
    // Invalidate before SQL, including staging/unknown writes; fresh SELECT/INSERT must reprove.
    invalidateRelations(writes);
    context.retainScratch(relationScratch, relationBytes);
    dependencyGeneration = Math.incrementExact(dependencyGeneration);
    releaseCreated();
    for (var invalidate : invalidators.values()) invalidate.accept(writes);
    invalidateProof(writes);
  }

  private void invalidateRelations(java.util.Set<Dependency> writes) {
    var entries = relations.entrySet().iterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      context.operation(1);
      boolean remove = writes == null;
      if (writes != null)
        for (var write : writes) {
          context.operation(1);
          if (entry.getKey().tenant().equals(write.tenant())
              && (write.task() == null || entry.getValue().task().equals(write.task())))
            remove = true;
        }
      if (remove) entries.remove();
    }
    relationBytes = RELATION_ROOT_BYTES + relationTableBytes;
    for (var e : relations.entrySet())
      relationBytes +=
          relationEntryBytes(
              e.getKey().tenant(),
              e.getKey().id(),
              e.getValue().task(),
              e.getValue().parent(),
              e.getValue().category());
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
    if (proofFailure != null) throw proofFailure;
    if (!sealed || generation != sealedGeneration)
      throw new IllegalStateException("Unsealed session transaction");
    if (expiry != null) expiry.run();
    context.remainingMillis();
  }

  void complete() {
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
    relationBytes = 0;
    context.releaseScratch(relationScratch);
    context.releaseScratch(stagingScratch);
    completed = true;
    context.releaseScratch(scopeScratch);
    context.releaseScratch(graphScratch);
  }
}
