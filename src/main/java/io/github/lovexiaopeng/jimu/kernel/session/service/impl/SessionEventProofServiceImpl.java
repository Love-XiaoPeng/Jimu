package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionEventProofService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** 版本、正文及来源证明严格沿用原顺序；trace仅将原400转换为损坏。 */
public final class SessionEventProofServiceImpl implements SessionEventProofService {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  public void verify(Proof proof) {
    try {
      verifyEvent(proof);
    } catch (TaskFailure failure) {
      if (proof.subject().trace() && failure.status() == 400) throw corrupt();
      throw failure;
    }
  }

  private void verifyEvent(Proof proof) {
    var task = proof.subject();
    if (task.scoped()) proof.operation();
    var r = proof.stored();
    if (!task.tenant().equals(r.tenant()) || !task.task().equals(r.task())) throw corrupt();
    if (r.recordVersion() != 1 || r.eventVersion() != task.version()) throw unsupported();
    if (!"PRESENT".equals(r.content())) throw new TaskFailure(410, "SESSION_HISTORY_EXPIRED");
    io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs.Document value;
    try {
      value = proof.decode();
    } catch (TaskFailure invalid) {
      throw corrupt();
    }
    if (!value.integralVersion()
        || value.version() != task.version()
        || !List.of("user.message", "step.started", "model.response", "session.read", "turn.ended")
            .contains(value.type())) throw unsupported();
    proof.validateDocument();
    if (proof.bytes(r.raw()) != r.bytes() || !proof.hash(r.raw()).equals(r.digest()))
      throw corrupt();
    if (!task.task().equals(value.session())
        || value.seq() != r.seq()
        || !r.type().equals(value.type())
        || !UTC.format(r.time()).equals(value.time())
        || !Objects.equals(r.step(), value.step())
        || !Objects.equals(r.invocation(), value.invocation())) throw corrupt();
    String digest;
    switch (r.type()) {
      case "user.message" -> {
        if (r.seq() != 0 || !r.fact().equals("accepted-user") || !proof.sourceMatches("request"))
          throw corrupt();
        digest = task.requestDigest();
      }
      case "step.started", "model.response" -> {
        var st = proof.step();
        boolean model = r.type().equals("model.response");
        if (!r.fact().equals((model ? "response:" : "step:") + st.id())
            || !value.sourceKind().equals(model ? "model" : "step")
            || value.invocation() != null) throw corrupt();
        String snapshot = model ? st.response() : st.request();
        digest = model ? st.responseDigest() : st.requestDigest();
        if (snapshot == null
            || !proof.hash(snapshot).equals(digest)
            || !model && value.ordinal() != st.ordinal()) throw corrupt();
      }
      case "session.read" -> {
        if (task.scoped()) {
          digest = proof.scopeRead();
          break;
        }
        var i = proof.invocation();
        var b = proof.read();
        if (!r.fact().equals("read:" + i.id())
            || !value.sourceKind().equals("invocation")
            || b.receipt() != r.seq()
            || !b.outcome().equals(i.state())
            || !proof.readReceipt().equals(value.data())) throw corrupt();
        if (i.state().equals("SUCCEEDED")
            && (!proof.hash(i.output()).equals(b.outputDigest())
                || !b.outputDigest().equals(i.outputDigest())
                || proof.bytes(i.output()) != b.outputBytes())) throw corrupt();
        if (task.trace()) proof.readRelation();
        digest = i.inputDigest();
      }
      case "turn.ended" -> {
        var terminal = proof.terminal();
        digest = terminal.digest();
        if (!r.fact().equals("terminal")
            || !proof.sourceMatches("task")
            || !Objects.equals(terminal.status(), value.state())
            || !Objects.equals(terminal.code(), value.code())) throw corrupt();
      }
      default -> throw unsupported();
    }
    if (!digest.equals(r.sourceDigest())) throw corrupt();
    if (task.trace() && !"session.read".equals(r.type()) && !value.sourcesEmpty()) throw corrupt();
    if (task.scoped() && !"session.read".equals(r.type()) && !value.refsEmpty()) throw corrupt();
    if (!"session_history.changed".equals(proof.reliableType(r.reliable()))) throw corrupt();
    if (r.attempt() != null) proof.requireAttempt(r.attempt(), r.fence());
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }

  private static TaskFailure unsupported() {
    return new TaskFailure(409, "SESSION_HISTORY_VERSION_UNSUPPORTED");
  }
}
