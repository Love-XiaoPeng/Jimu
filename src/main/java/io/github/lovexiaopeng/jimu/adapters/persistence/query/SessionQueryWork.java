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
  private final java.util.Set<String> locks = new java.util.HashSet<>();
  private final Object scopeScratch = new Object(), graphScratch = new Object();
  private String fenceKey, leaseKey, stepKey, responseKey;

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
  private String stagedGraphKey;
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
    for (var f : stagedSources.values()) bytes = Math.addExact(bytes, sourceBytes(f));
    if (!stagedSources.containsKey(fact.caller().id()))
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
    proofFailure = failure;
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
    mutation();
    if (scope != null) {
      var failure = new IllegalStateException("Session proof dependency changed after publication");
      proofFailure = failure;
      throw failure;
    }
    stagedGraph = null;
    stagedGraphKey = null;
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
  }

  private void holdGraph(VerifiedGraph proof) {
    long bytes = 1024;
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
    if (!sealed || generation != sealedGeneration)
      throw new IllegalStateException("Unsealed session transaction");
    if (expiry != null) expiry.run();
    context.remainingMillis();
  }

  void complete() {
    accounts.clear();
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
    stagingCaller = null;
    context.releaseScratch(stagingScratch);
    completed = true;
    context.releaseScratch(scopeScratch);
    context.releaseScratch(graphScratch);
  }
}
