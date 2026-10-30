package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;

/** 真实MyBatis故障在同query的REQUIRES_NEW中登记后，必须恢复外层清理同步器。 */
class SessionQueryResumeFailureTest {
  @Intercepts({
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class})
  })
  static class DriverFailure implements Interceptor {
    final SQLException failure = new SQLException("test-only inner prepare failure", "HY778");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      throw failure;
    }
  }

  @Test
  void caughtInnerMyBatisFailureStillRestoresOuterSynchronizationAndReleasesEveryOwner()
      throws Exception {
    var f = new SessionHistoryLengthTest.Fixture();
    var q = SessionEventHistoryLengthTest.query();
    var failure = new DriverFailure();
    RuntimeException[] first = {null};
    SessionQueryWork[] owner = {null};
    var caught =
        assertThrows(
            RuntimeException.class,
            () ->
                f.work(
                    q,
                    () -> {
                      owner[0] = SessionQueryTransaction.current(q);
                      owner[0].beginHistory("t", "x");
                      f.sql.getSqlSessionFactory().getConfiguration().addInterceptor(failure);
                      assertThrows(
                          RuntimeException.class,
                          () ->
                              f.transaction.requiresNew(
                                  q,
                                  () -> {
                                    SessionQueryTransaction.current(q).locked("t", "x");
                                    f.load(q); // 真实Executor catch登记首错；没有反射直调的人工poison。
                                    return null;
                                  }));
                      first[0] = q.recordedFailure();
                      assertNotNull(first[0]);
                      return null;
                    }));
    System.out.println(
        "RESUME_FAILURE caught="
            + caught.getClass().getSimpleName()
            + " first="
            + first[0].getClass().getSimpleName()
            + " same="
            + (caught == first[0])
            + " commits="
            + f.database.commits
            + " rollbacks="
            + f.database.rollbacks
            + " live="
            + q.scratchLive()
            + " expectedLive=65536");
    assertSame(first[0], caught);
    assertEquals(0, f.database.commits);
    assertEquals(2, f.database.rollbacks);
    assertEquals(65536, q.scratchLive());
    assertEquals(true, SessionHistoryStatementIdentityTest.field(owner[0], "completed"));
    assertNull(SessionHistoryStatementIdentityTest.field(owner[0], "historyLength"));
    boolean source = false;
    for (Throwable cursor = caught; cursor != null; cursor = cursor.getCause())
      if (cursor == failure.failure) source = true;
    assertTrue(source);
    assertEquals("HY778", failure.failure.getSQLState());
  }
}
