package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Graph;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Node;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored;
import io.github.lovexiaopeng.jimu.kernel.session.domain.SessionLineageGraph;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionLineageRecords.Fact;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

/** 仅加载显式节点的一层事实和genesis；来源graph只能与本次Map比较，绝不递归读库。 */
public final class SessionLineageServiceImpl implements SessionLineageService {
  private final SessionLineageRecords records;
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeQueryService slots;

  public SessionLineageServiceImpl(SessionLineageRecords records) {
    this(records, null);
  }

  public SessionLineageServiceImpl(
      SessionLineageRecords records,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeQueryService slots) {
    this.records = records;
    this.slots = slots;
  }

  public String traceSlot(
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease,
      String step,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call call,
      String version,
      String digest) {
    if (slots == null) throw new IllegalStateException("Original slot service required");
    return slots.scopedOrResult(lease, step, call, version, digest);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static TaskFailure invalidScope() {
    return new TaskFailure(400, SessionErrorCode.SESSION_LINEAGE_SCOPE_INVALID);
  }

  public void create(String tenant, String caller, String parent) {
    try {
      records.withProof(
          () -> {
            createGraph(tenant, caller, parent);
            return null;
          });
    } finally {
      records.scratch(0);
    }
  }

  private void createGraph(String tenant, String caller, String parent) {
    var fact = records.fact(tenant, caller);
    var facts = new HashMap<String, Fact>();
    facts.put(caller, fact);
    var inputs = new HashMap<String, Stored>();
    if (!Objects.equals(parent, fact.declaredParent())
        || (parent != null && !fact.sources().contains(parent))) throw invalidScope();
    var nodes = new HashMap<String, Node>();
    for (var id : fact.sources()) {
      var stored = records.load(tenant, id);
      facts.put(id, records.fact(tenant, id));
      inputs.put(id, stored);
      nodes.put(id, new Node(id, stored.genesis(), stored.genesisDigest()));
    }
    var genesis =
        new Genesis(
            1,
            caller,
            parent == null ? "ROOT" : "CONTINUATION",
            fact.created(),
            fact.requestDigest(),
            fact.authorityDigest(),
            fact.bindingDigest(),
            parent,
            parent == null ? null : nodes.get(parent).genesisSha256());
    String raw = records.genesis(genesis);
    nodes.put(caller, new Node(caller, raw, records.hash(raw)));
    String graph =
        records.graph(
            new Graph(
                1,
                caller,
                fact.authorityDigest(),
                nodes.values().stream()
                    .sorted(java.util.Comparator.comparing(Node::sessionId))
                    .toList()));
    var stored =
        new Stored(
            raw,
            records.hash(raw),
            records.bytes(raw),
            graph,
            records.hash(graph),
            records.bytes(graph),
            "PRESENT",
            1);
    records.insert(tenant, caller, stored);
    records.body(tenant, caller, records.bytes(raw) + records.bytes(graph));
    inputs.put(caller, records.load(tenant, caller));
    records.created(tenant, caller, validate(tenant, caller, facts, inputs));
  }

  private void raw(Stored s) {
    records.checkpoint(1);
    if (s.version() != 1
        || !"PRESENT".equals(s.state())
        || s.genesis() == null
        || s.graph() == null
        || records.bytes(s.genesis()) != s.genesisBytes()
        || s.genesisBytes() > 1024
        || records.bytes(s.graph()) != s.graphBytes()
        || s.graphBytes() > 16384
        || !records.hash(s.genesis()).equals(s.genesisDigest())
        || !records.hash(s.graph()).equals(s.graphDigest())) throw corrupt();
  }

  private VerifiedGraph load(String tenant, String caller) {
    var cached = records.verified(tenant, caller);
    if (cached != null) return cached;
    try {
      return records.withProof(() -> loadUncached(tenant, caller));
    } finally {
      records.scratch(0);
    }
  }

  private VerifiedGraph loadUncached(String tenant, String caller) {
    var facts = new HashMap<String, Fact>();
    facts.put(caller, records.fact(tenant, caller));
    return validate(tenant, caller, facts, new HashMap<>());
  }

  private VerifiedGraph validate(
      String tenant, String caller, HashMap<String, Fact> facts, HashMap<String, Stored> stored) {
    var fact = facts.get(caller);
    long scratch = 3L * records.bytes(fact.request());
    records.scratch(scratch);
    if (fact.sources().size() > 8 || fact.sources().contains(caller)) throw corrupt();
    var ids = new ArrayList<>(fact.sources());
    ids.add(caller);
    if (ids.stream().distinct().count() != ids.size()) throw corrupt();
    var nodes = new HashMap<String, Node>();
    var genesis = new HashMap<String, Genesis>();
    for (var id : ids) {
      var f = facts.computeIfAbsent(id, key -> records.fact(tenant, key));
      if (!ids.containsAll(f.sources())) throw invalidScope();
      var s = stored.computeIfAbsent(id, key -> records.load(tenant, key));
      raw(s);
      scratch =
          Math.addExact(
              scratch,
              3L
                  * (records.bytes(s.graph())
                      + records.bytes(s.genesis())
                      + (id.equals(caller) ? 0 : records.bytes(f.request()))));
      records.scratch(scratch);
      var g = records.genesis(s.genesis());
      if (g.version() != 1
          || !id.equals(g.sessionId())
          || !id.equals(f.id())
          || !records.requestVerified(f) && !records.hash(f.request()).equals(f.requestDigest())
          || !g.requestSha256().equals(f.requestDigest())
          || !g.authoritySha256().equals(f.authorityDigest())
          || !g.bindingSha256().equals(f.bindingDigest())
          || !g.createdAt().equals(f.created())
          || !Objects.equals(g.parentSessionId(), f.declaredParent())
          || (g.parentSessionId() != null && !f.sources().contains(g.parentSessionId())))
        throw corrupt();
      facts.put(id, f);
      stored.put(id, s);
      genesis.put(id, g);
      nodes.put(id, new Node(id, s.genesis(), s.genesisDigest()));
    }
    int dependencies = 0;
    for (var id : ids) {
      var f = facts.get(id);
      var s = stored.get(id);
      var graph = records.graph(s.graph());
      dependencies += id.equals(caller) ? 0 : f.sources().size();
      if (dependencies > 64) throw corrupt();
      var expected = new ArrayList<>(f.sources());
      expected.add(id);
      expected.sort(String::compareTo);
      if (graph.version() != 1
          || !id.equals(graph.callerSessionId())
          || !f.authorityDigest().equals(graph.authoritySha256())
          || !graph.nodes().stream().map(Node::sessionId).toList().equals(expected))
        throw corrupt();
      for (var n : graph.nodes()) {
        records.checkpoint(1);
        if (!n.equals(nodes.get(n.sessionId()))) throw corrupt();
      }
    }
    SessionLineageGraph.validate(genesis, nodes, records::checkpoint);
    var proof =
        new VerifiedGraph(
            fact.authorityDigest(),
            stored.get(caller).graphDigest(),
            stored.get(caller).genesisDigest(),
            genesis,
            nodes);
    records.verified(tenant, caller, proof);
    return proof;
  }

  public VerifiedGraph verify(String tenant, String caller) {
    return load(tenant, caller);
  }

  public String trace(String tenant, String caller, String target) {
    return observe(tenant, caller, target).output();
  }

  public Observation observe(String tenant, String caller, String target) {
    var v = load(tenant, caller);
    if (target == null) target = caller;
    if (!v.nodes().containsKey(target))
      throw new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
    String output =
        records.output(
            SessionLineageGraph.trace(
                caller,
                target,
                v.authorityDigest(),
                v.graphDigest(),
                v.genesis(),
                v.nodes(),
                records::checkpoint));
    var proof = new StringBuilder();
    for (var id : v.nodes().keySet().stream().sorted().toList()) {
      var g = v.genesis().get(id);
      proof
          .append(id)
          .append(':')
          .append(v.nodes().get(id).genesisSha256())
          .append(':')
          .append(g.parentSessionId() == null ? "ROOT" : g.parentSessionId())
          .append('\n');
    }
    return new Observation(
        output,
        records.hash(proof.toString()),
        v.nodes().size(),
        (int) v.genesis().values().stream().filter(g -> g.parentSessionId() != null).count());
  }
}
