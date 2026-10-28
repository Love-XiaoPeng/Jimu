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
    graph = proof;
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
    completed = true;
    context.releaseScratch(scopeScratch);
    context.releaseScratch(graphScratch);
  }
}
