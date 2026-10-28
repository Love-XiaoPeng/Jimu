package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅记录显式查询的阶段耗时与累计计量差值；不输出正文、SQL、身份或权限快照。 */
public final class SessionQueryDiagnostics {
  private static final Logger LOG = LoggerFactory.getLogger(SessionQueryDiagnostics.class);

  private SessionQueryDiagnostics() {}

  public static <T> T measure(SessionQueryContext query, String stage, Supplier<T> work) {
    if (query == null) return work.get();
    long start = System.nanoTime();
    var before = query.resources();
    boolean success = false;
    try {
      T result = work.get();
      success = true;
      return result;
    } finally {
      var after = query.resources();
      LOG.info(
          "Session query stage={} success={} elapsedMicros={} reads={} bytes={} operations={} totalReads={} remainingMicros={}",
          stage,
          success,
          (System.nanoTime() - start) / 1000,
          after.reads() - before.reads(),
          after.bytes() - before.bytes(),
          after.operations() - before.operations(),
          after.reads(),
          (query.deadlineNanos() - System.nanoTime()) / 1000);
    }
  }
}
