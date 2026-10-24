package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeSearchExecution;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionNativeSearchExecutionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;

/** history锁先行，空范围短路，逐项截止/证明/字节/scan/matcher及finally预算清理。 */
public final class SessionNativeSearchExecutionServiceImpl
    implements SessionNativeSearchExecutionService {
  public SessionNativeSearchExecution.Result search(SessionNativeSearchExecution execution) {
    var history = execution.history();
    if (!execution.enabled()) throw new TaskFailure(409, "SESSION_SEARCH_UNAVAILABLE");
    execution.normalized();
    var matcher = execution.matcher();
    var scanned = new ArrayList<SessionNativeSearchExecution.Scan>();
    int bytes = 0;
    try (var ignored = execution.budget()) {
      long upper = execution.upper();
      if ((execution.from() == null ? 0 : execution.from())
          <= Math.min(upper, execution.to() == null ? upper : execution.to())) {
        if (upper < 0 || upper >= 128 || upper >= history.next()) throw corrupt();
        var events = execution.scan();
        if (events.size() != upper + 1) throw corrupt();
        for (int index = 0; index < events.size(); index++) {
          execution.deadline();
          var event = events.get(index);
          if (event.seq() != index) throw corrupt();
          var value = event.verify();
          bytes += event.snapshotBytes();
          if (bytes > 2097152) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
          scanned.add(new SessionNativeSearchExecution.Scan(index, event.digest()));
          matcher.accept(value, event.digest(), upper);
        }
      }
      String output = matcher.output();
      execution.validate(output);
      return new SessionNativeSearchExecution.Result(
          output, execution.scanDigest(scanned), scanned.size(), bytes);
    }
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
  }
}
