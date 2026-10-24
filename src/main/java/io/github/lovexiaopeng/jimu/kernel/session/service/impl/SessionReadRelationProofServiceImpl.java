package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReadRelationProofs;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionReadRelationProofService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;

/** 保留来源/step/输入/槽位/pin/结果/直接端点的证明顺序与失败传播。 */
public final class SessionReadRelationProofServiceImpl implements SessionReadRelationProofService {
  public List<SessionReadRelationProofs.Source> sources(SessionReadRelationProofs.Sources p) {
    var b = p.sourceBinding();
    String raw = b.raw();
    if (b.version() != 1
        || !"PRESENT".equals(b.state())
        || raw == null
        || p.bytes(raw) > 1024
        || p.bytes(raw) != b.bytes()
        || !p.hash(raw).equals(b.digest())) throw corrupt();
    var document = p.sourceDocument(raw);
    if (!document.array() || document.size() > 9) throw corrupt();
    long previous = -1;
    for (var source : document.values()) {
      if (!source.object()
          || source.fields() != 2
          || !source.integral()
          || source.seq() <= previous
          || source.seq() > 127
          || !source.digest().matches("[0-9a-f]{64}")) throw corrupt();
      previous = source.seq();
    }
    return List.copyOf(document.values());
  }

  public void verify(SessionReadRelationProofs.Proof p) {
    var sources = sources(p);
    if (!p.sequences(sources).equals(p.eventSequences())) throw corrupt();
    var st = p.step();
    p.validateStep();
    var b = p.binding();
    var i = p.invocation();
    if (!p.historyBinding().equals(b.binding())
        || st.upper().longValue() != b.upper()
        || !p.hash(i.input()).equals(i.inputDigest())
        || !i.inputDigest().equals(b.inputDigest())
        || !"session_event_read".equals(i.tool())) throw corrupt();
    var input = p.input(i.input());
    if (input.seq() != b.target()
        || input.before() != b.before()
        || input.after() != b.after()
        || input.session() != null && !input.session().equals(p.session())) throw corrupt();
    if (!p.hash(st.response()).equals(st.responseDigest())) throw corrupt();
    var response = p.response(st.response());
    boolean matched = false;
    for (int index = 0; index < response.calls().size(); index++) {
      var call = response.calls().get(index);
      if ((index + ":" + call.id()).equals(i.slot())
          && call.id().equals(i.provider())
          && call.name().equals(i.tool())
          && call.arguments().equals(i.input())) matched = true;
    }
    var pin = p.pin();
    if (!matched
        || response.version() != 2
        || pin == null
        || !pin.version().equals(i.pluginVersion())
        || !pin.digest().equals(i.pluginDigest())
        || pin.epoch() != i.epoch()) throw corrupt();
    if ("FAILED".equals(b.outcome())) {
      if (!sources.isEmpty() || i.output() != null || b.outputBytes() != 0) throw corrupt();
      return;
    }
    if (!"SUCCEEDED".equals(b.outcome()) || sources.isEmpty()) throw corrupt();
    String raw = i.output();
    p.validateOutput(raw);
    var output = p.output(raw);
    long from = Math.max(0, input.seq() - input.before());
    long to = Math.min(b.upper(), input.seq() + input.after());
    if (sources.size() != to - from + 1
        || b.start() != from
        || b.end() != to
        || output.upper() != b.upper()
        || !"pre-step".equals(output.boundary())
        || !p.session().equals(output.session())
        || output.targetSeq() != input.seq()
        || output.from() != from
        || output.to() != to
        || output.before() != input.seq() - from
        || output.after() != to - input.seq()) throw corrupt();
    int index = 0;
    for (var source : sources) {
      long seq = source.seq();
      if (seq != from + index++ || seq > b.upper() || seq >= p.eventSeq()) throw corrupt();
      String endpoint = p.endpointDigest(seq);
      if (!endpoint.equals(source.digest())) throw corrupt();
      if (seq == input.seq()
          && (!endpoint.equals(b.targetDigest())
              || !p.hash(output.target()).equals(b.targetDigest()))) throw corrupt();
    }
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }
}
