package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionNativeTraceExecutionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;

/** 原history锁/探针顺序、有界扫描和直接关系证明；两路计量及finally清预算。 */
public final class SessionNativeTraceExecutionServiceImpl
    implements SessionNativeTraceExecutionService {
  public SessionNativeTraceExecution.Result trace(SessionNativeTraceExecution execution) {
    var history = execution.history();
    if (!execution.enabled()) throw new TaskFailure(409, "SESSION_TRACE_UNAVAILABLE");
    if (execution.requestedSession() != null
            && !execution.requestedSession().equals(execution.session())
        || execution.seq() > execution.upper()) throw missing();
    long upper = execution.upper();
    if (upper < 0 || upper >= 128 || history.next() > 128 || upper >= history.next())
      throw corrupt();
    if (!execution.exists()) throw missing();
    try (var budget = execution.budget()) {
      var records = execution.scan();
      if (records.size() != upper + 1) throw corrupt();
      var scanned = new ArrayList<SessionNativeTraceExecution.Scan>();
      var relations = new ArrayList<SessionNativeTraceExecution.Relation>();
      var derived = new ArrayList<Long>();
      SessionNativeTraceExecution.Value target = null;
      String targetDigest = null;
      int eventBytes = 0, edges = 0, receipts = 0;
      long operations = 0;
      for (int index = 0; index < records.size(); index++) {
        execution.deadline();
        var event = records.get(index);
        if (event.seq() != index) throw corrupt();
        eventBytes += event.snapshotBytes();
        if (eventBytes > 2097152) throw limit();
        var value = event.verify();
        scanned.add(new SessionNativeTraceExecution.Scan(index, event.digest()));
        if (index == execution.seq()) {
          target = value;
          targetDigest = event.digest();
        }
        if (event.read()) {
          var receipt = event.receipt();
          relations.add(new SessionNativeTraceExecution.Relation(index, receipt.digest()));
          if ("SUCCEEDED".equals(receipt.outcome()) && ++receipts > 20) throw limit();
          for (var source : value.sources()) {
            operations++;
            if (++edges > 180) throw limit();
            long seq = source.seq();
            if (seq < 0 || seq >= index || seq >= records.size()) throw corrupt();
            if (seq == execution.seq()) derived.add((long) index);
          }
        }
        operations += 32L + event.characters();
        budget.operation(32L + event.characters() + value.sourceCount());
        if (operations > 1048576) throw limit();
      }
      if (target == null) throw missing();
      if (derived.size() > 20) throw limit();
      var summary = execution.summary(target, targetDigest);
      if (summary.bytes() > 2048) throw limit();
      String output = execution.output(summary, target, derived);
      execution.validate(output);
      execution.deadline();
      return new SessionNativeTraceExecution.Result(
          output,
          execution.scanDigest(scanned),
          execution.relationsDigest(relations),
          targetDigest,
          records.size(),
          eventBytes,
          edges);
    }
  }

  private static TaskFailure missing() {
    return new TaskFailure(404, "SESSION_EVENT_NOT_FOUND");
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }

  private static TaskFailure limit() {
    return new TaskFailure(429, "SESSION_HISTORY_LIMIT");
  }
}
