package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryRetentionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SessionRetentionBatchFailureTest {
  @Test
  void primaryCandidateFailureSurvivesCloseFailureAndEmptyBatchCloseFailurePropagates() {
    for (boolean empty : List.of(false, true)) {
      var primary = new IllegalArgumentException("candidate");
      var cleanup = new IllegalStateException("batch close");
      var batch =
          new SessionHistoryRetentions.CandidateBatch() {
            public java.util.Iterator<SessionHistoryRetentions.Candidate> iterator() {
              return (empty
                      ? List.<SessionHistoryRetentions.Candidate>of()
                      : List.of(new SessionHistoryRetentions.Candidate("t", "task")))
                  .iterator();
            }

            public void close() {
              throw cleanup;
            }
          };
      var records =
          (SessionHistoryRetentions)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionHistoryRetentions.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("candidateBatch")) return batch;
                    throw new AssertionError(method.getName());
                  });
      var work =
          new LedgerUnitOfWork() {
            public <T> T required(Supplier<T> operation) {
              throw primary;
            }
          };
      var service = new SessionHistoryRetentionServiceImpl(records, work, Payloads::hash);
      var failure = assertThrows(RuntimeException.class, service::cleanup);
      assertSame(empty ? cleanup : primary, failure);
      if (!empty) assertSame(cleanup, failure.getSuppressed()[0]);
    }
  }
}
