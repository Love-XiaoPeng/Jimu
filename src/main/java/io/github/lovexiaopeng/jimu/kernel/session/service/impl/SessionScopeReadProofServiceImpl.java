package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionOutcome;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionScopeOperation;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeReadProofService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;

/** 保留原槽/step/scope/切点顺序、两次refs读取及失败传播，不重放provider。 */
public final class SessionScopeReadProofServiceImpl implements SessionScopeReadProofService {
  private final io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeNormalizationService
      normalization = new SessionScopeNormalizationServiceImpl();

  public String verify(Proof proof) {
    var i = proof.invocation();
    var b = proof.binding();
    var step = proof.step();
    proof.validateStep();
    String authority = proof.authority();
    var cut = proof.cut();
    String normalizedInput = proof.normalizedInput();
    normalization.verify(
        normalizedInput,
        new io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Stored(
            b.normalized(), b::normalizedDigest, b::normalizedBytes),
        proof);
    var event = proof.event();
    if (!SessionScopeOperation.READ.equals(b.operation())
        || !ToolCapability.SESSION_EVENT_READ.equals(i.tool())
        || !proof.hash(i.input()).equals(i.inputDigest())
        || !authority.equals(b.authority())
        || !b.authority().equals(cut.authority())
        || !step.context().equals(b.context())
        || !b.context().equals(cut.context())
        || !proof.hash(cut.rawContext()).equals(cut.context())
        || !step.binding().equals(b.binding())
        || !event.fact().equals("read:" + i.id())
        || !event.sourceKind().equals("invocation")
        || b.nativeSeq() != event.seq()
        || !i.state().equals(b.outcome())
        || !i.inputDigest().equals(b.inputDigest())
        || !proof.receipt().equals(event.data())
        || !proof.refs().canonical().equals(event.refs())) throw corrupt();
    var refs = proof.refs().values();
    if (SessionOutcome.SUCCEEDED.equals(b.outcome())) {
      String raw = i.output();
      proof.validateOutput(raw);
      if (!proof.hash(raw).equals(b.outputDigest())
          || !b.outputDigest().equals(i.outputDigest())
          || proof.bytes(raw) != b.outputBytes()) throw corrupt();
      var output = proof.output(raw);
      var normalized = proof.normalized(b.normalized());
      long targetSeq = normalized.seq();
      long from = Math.max(0, targetSeq - normalized.before());
      long to = Math.min(normalized.upper(), targetSeq + normalized.after());
      if (!output.session().equals(normalized.session())
          || output.upper() != normalized.upper()
          || output.from() != from
          || output.to() != to
          || output.before().size() != targetSeq - from
          || output.after().size() != to - targetSeq
          || refs.size() != to - from + 1) throw corrupt();
      var endpoints = proof.endpoints(normalized.session(), from, to);
      if (endpoints.size() != refs.size()) throw corrupt();
      for (int index = 0; index < refs.size(); index++) {
        var ref = refs.get(index);
        var endpoint = endpoints.get(index);
        long seq = ref.seq();
        String projected =
            seq == targetSeq
                ? output.target()
                : seq < targetSeq
                    ? at(output.before(), index)
                    : at(output.after(), (int) (seq - targetSeq - 1));
        if (!ref.session().equals(output.session()) || seq != from + index || seq != endpoint.seq())
          throw corrupt();
        String endpointRaw = endpoint.raw();
        if (proof.bytes(endpointRaw) != endpoint.bytes()
            || !proof.hash(endpointRaw).equals(endpoint.digest())
            || !endpoint.digest().equals(ref.digest())) throw corrupt();
        proof.validateEvent(endpointRaw);
        var source = proof.source(endpointRaw);
        if (source.seq() != seq
            || !source.session().equals(ref.session())
            || projected == null
            || !(seq == targetSeq ? source.canonical() : proof.sourceSummary()).equals(projected))
          throw corrupt();
      }
      var target =
          refs.stream()
              .filter(ref -> ref.seq() == normalized.seq())
              .findFirst()
              .orElseThrow(SessionScopeReadProofServiceImpl::corrupt);
      if (!proof.hash(output.target()).equals(target.digest())) throw corrupt();
    } else if (!refs.isEmpty() || i.output() != null || b.outputBytes() != 0) throw corrupt();
    return i.inputDigest();
  }

  // 沿用 JsonNode.get 的越界 NULL；先按原顺序核 refs 序号，再拒绝缺失投影。
  private static String at(java.util.List<String> values, int index) {
    return index < 0 || index >= values.size() ? null : values.get(index);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }
}
