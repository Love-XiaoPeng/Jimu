package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionFacts;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionFactService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserve reliable-event recursion, lazy row access and each independent accounting call. */
public final class SessionFactServiceImpl implements SessionFactService {
  public long append(String fact, SessionFacts.Append records) {
    String changed = "history:" + fact;
    records.changed(changed);
    long reliable = records.reliable(changed);
    long seq = records.append(reliable);
    records.account(records.terminal());
    return seq;
  }

  public void record(String fact, SessionFacts.Fact records) {
    if (!records.session()) return;
    if (fact.equals("accepted")) {
      var text = records.goalText();
      records.append(
          "accepted-user",
          "user.message",
          records.source("request", null, null),
          Map.of("content", text.content()),
          text.redacted(),
          records.requestDigest(),
          null);
    } else if (fact.startsWith("prepared:") || fact.startsWith("response:")) {
      boolean model = fact.startsWith("response:");
      String stepId = fact.substring(fact.indexOf(':') + 1);
      var step = records.step(stepId);
      if (model && !"RESPONDED".equals(step.state())) return;
      var attempt = records.attempt();
      Lease lease =
          new Lease(
              records.tenant(),
              records.owner(),
              records.taskId(),
              attempt.id(),
              attempt.fence(),
              records.worker(),
              records.deadline(),
              records.goal(),
              records.caps(),
              records.installationEpoch());
      Map<String, Object> data;
      boolean redacted = false;
      if (model) {
        var response = step.response();
        var text = response.text();
        var projected = response.project();
        redacted = text.redacted() || projected.redacted();
        data = Map.of("content", text.content(), "calls", projected.calls());
      } else data = Map.of("ordinal", step.ordinal());
      records.append(
          (model ? "response:" : "step:") + stepId,
          model ? "model.response" : "step.started",
          records.source(model ? "model" : "step", stepId, null),
          data,
          redacted,
          model ? step.responseDigest() : step.requestDigest(),
          lease);
    } else if (fact.equals("terminal")) {
      var terminal = records.terminal();
      var data = new LinkedHashMap<String, Object>();
      data.put("state", terminal.state());
      data.put("code", terminal.code());
      records.append(
          "terminal",
          "turn.ended",
          records.source("task", null, null),
          data,
          false,
          terminal.digest(),
          null);
    }
  }
}
