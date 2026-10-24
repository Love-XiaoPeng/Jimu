package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Prior;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.ReadResult;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Ref;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.ScopeRead;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.SearchResult;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Value;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Window;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeExecutionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;

/** 原require/输入来源/matcher/预算顺序，逐项截止/证明/refs/匹配及finally清理。 */
public final class SessionScopeExecutionServiceImpl implements SessionScopeExecutionService {
  public SearchResult prior(Prior prior) {
    var scope = prior.require();
    for (String id : prior.sessions())
      if (!scope.contains(id)) throw new TaskFailure(404, "SESSION_SCOPE_UNAVAILABLE");
    var matcher = prior.matcher();
    var scanned = new ArrayList<Ref>();
    int eventBytes = 0;
    try (var ignored = prior.budget()) {
      for (int sourceIndex = 0; sourceIndex < scope.size(); sourceIndex++) {
        var source = scope.source(sourceIndex);
        var cut = scope.cut(sourceIndex);
        long upper = cut.upper();
        var records = source.scan(upper);
        if (records.size() != upper + 1) throw corrupt();
        for (int index = 0; index < records.size(); index++) {
          prior.deadline();
          var record = records.get(index);
          if (record.seq() != index || scanned.size() >= 1024) throw corrupt();
          int bytes = record.snapshotBytes();
          if (bytes > 16384 || bytes != record.storedBytes()) throw corrupt();
          eventBytes += bytes;
          if (eventBytes > 4194304) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          var value = record.verify();
          scanned.add(source.ref(index, record.digest()));
          matcher.accept(value, record.digest(), cut);
        }
      }
      String output = matcher.output(prior.caller(), scope.digest());
      prior.validate(output);
      return new SearchResult(output, prior.scanDigest(scanned), scanned.size(), eventBytes);
    }
  }

  public ReadResult read(ScopeRead read) {
    var scope = read.require();
    var normalized = read.normalized();
    String id = normalized.target();
    var target = id.equals(read.callerId()) ? read.caller() : scope.find(id);
    try (var ignored = read.budget()) {
      return window(
          target.window(
              normalized.input(id),
              normalized.upper(),
              id.equals(read.callerId()) ? "pre-step" : "scoped-admission"));
    }
  }

  public ReadResult window(Window window) {
    window.history();
    var input = window.input();
    long upper = window.upper();
    if (input.sessionId() != null && !input.sessionId().equals(window.session())
        || input.seq() > upper) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    long from = Math.max(0, input.seq() - input.before());
    long to = Math.min(upper, input.seq() + input.after());
    var records = window.events(from, to);
    if (records.size() != to - from + 1) throw corrupt();
    var before = new ArrayList<Value>();
    var after = new ArrayList<Value>();
    Value target = null;
    String targetDigest = null;
    var refs = new ArrayList<Ref>();
    for (int index = 0; index < records.size(); index++) {
      var record = records.get(index);
      if (record.seq() != from + index) throw corrupt();
      var value = record.verify();
      refs.add(window.ref(record));
      if (record.seq() == input.seq()) {
        target = value;
        targetDigest = record.digest();
      } else {
        (record.seq() < input.seq() ? before : after).add(window.summary(value));
      }
    }
    if (target == null) throw new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
    return window.output(from, to, target, targetDigest, before, after, refs, records);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }
}
