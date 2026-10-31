package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实Spring同步器的所有权、挂起、提交尾部与不明完成；不是缓存方法mock。 */
class SessionQueryOwnershipTest {
  @Test
  void nestedRequiredRegistersUntilOwnerEndsAndClosingIsMonotonic() {
    var f = new SessionQueryTransactionTest.Fixture();
    var events = new ArrayList<String>();
    var held = new AtomicReference<SessionQueryWork>();
    f.execute(
        () -> {
          var outer = SessionQueryTransaction.current(f.context);
          held.set(outer);
          outer.account("task", true, c -> events.add("account:" + c));
          f.execute(
              () -> {
                assertSame(outer, SessionQueryTransaction.current(f.context));
                outer.account("task", false, c -> events.add("account:" + c));
                return null;
              });
          assertTrue(events.isEmpty());
          events.add("business-finished");
          return null;
        });
    assertEquals(List.of("business-finished", "account:true"), events);
    assertThrows(IllegalStateException.class, () -> held.get().token());
    assertNull(SessionQueryTransaction.current(f.context));
    assertEquals(1, f.commits);
  }

  @Test
  void requiresNewHasAnotherTokenThenRestoresUnsealedOuter() {
    var f = new SessionQueryTransactionTest.Fixture();
    var innerRef = new AtomicReference<SessionQueryWork>();
    f.execute(
        () -> {
          var outer = SessionQueryTransaction.current(f.context);
          var token = outer.token();
          f.transaction.requiresNew(
              f.context,
              () -> {
                var inner = SessionQueryTransaction.current(f.context);
                innerRef.set(inner);
                assertNotSame(token, inner.token());
                return null;
              });
          assertThrows(IllegalStateException.class, () -> innerRef.get().token());
          assertSame(outer, SessionQueryTransaction.current(f.context));
          outer.mutation();
          return null;
        });
    assertEquals(2, f.commits);
  }

  @Test
  void externalTransactionAndDifferentQueryCannotBorrowAuthority() {
    var f = new SessionQueryTransactionTest.Fixture();
    var external = new TransactionTemplate(new DataSourceTransactionManager(f.source));
    assertThrows(IllegalStateException.class, () -> external.execute(s -> f.execute(() -> null)));
    assertThrows(
        IllegalStateException.class,
        () ->
            f.execute(
                () ->
                    f.transaction.required(
                        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null),
                        () -> null)));
    assertEquals(0, f.commits);
  }

  @Test
  void finalizerFailureAndWriteAfterSealRollBackEverything() {
    for (boolean seal : List.of(false, true)) {
      var f = new SessionQueryTransactionTest.Fixture();
      assertThrows(
          IllegalStateException.class,
          () ->
              f.execute(
                  () -> {
                    var owner = SessionQueryTransaction.current(f.context);
                    if (seal)
                      TransactionSynchronizationManager.registerSynchronization(
                          new TransactionSynchronization() {
                            public void beforeCommit(boolean readOnly) {
                              owner.mutation();
                            }
                          });
                    else
                      owner.account(
                          "task",
                          false,
                          c -> {
                            throw new IllegalStateException("final readback");
                          });
                    return null;
                  }));
      assertEquals(0, f.commits);
      assertEquals(1, f.rollbacks);
    }
  }

  @Test
  void unknownCommitAndRollbackDestroyRetainedAuthority() {
    for (boolean commit : List.of(false, true)) {
      var f = new SessionQueryTransactionTest.Fixture();
      if (commit) f.commit = new SQLException("lost commit ACK");
      else f.rollback = new SQLException("lost rollback ACK");
      var held = new AtomicReference<SessionQueryWork>();
      assertThrows(
          TransactionSystemException.class,
          () ->
              f.execute(
                  () -> {
                    held.set(SessionQueryTransaction.current(f.context));
                    if (!commit) throw new IllegalStateException("failed write");
                    return null;
                  }));
      assertThrows(IllegalStateException.class, () -> held.get().token());
      assertNull(SessionQueryTransaction.current(f.context));
    }
  }
}
