package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.ModelWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.model.service.impl.ModelDispatchServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实Spring事务验证M2的提交/回滚边界；响应拒绝在用量和审计提交之后抛出。 */
class ModelDispatchServiceTest {
  private static final Lease LEASE =
      new Lease(
          "t",
          "owner",
          "task",
          "attempt",
          1,
          "worker",
          Instant.MAX,
          "goal",
          List.of("session_trace"),
          1);
  private static final ModelUsage USAGE =
      new ModelUsage(100L, 50L, 150L, null, null, null, null, null);
  private static final List<Call> CALLS =
      List.of(
          new Call("s1", "p1", "session_trace", "{}"),
          new Call("s2", "p2", "session_trace", "{}"),
          new Call("s3", "p3", "session_trace", "{}"));

  @Test
  void threeSlotsAndUsageCommitOnceBeforeOutcomeRead() {
    var f = new Fixture();
    f.respond();
    assertEquals(1, f.jdbc.commits);
    assertEquals(0, f.jdbc.rollbacks);
    assertEquals(3, f.calls);
    assertEquals(
        List.of("usage", "storeResponse", "settleTokens", "bindCalls", "responded", "outcome"),
        f.events);
  }

  @Test
  void payloadRejectionRetainsActualUsageAndAuditBeforeThrowing() {
    var f = new Fixture();
    f.rejectAt = "validatePayload";
    assertEquals("TOOL_INPUT_INVALID", assertThrows(TaskFailure.class, f::respond).code());
    assertEquals(1, f.jdbc.commits);
    assertEquals(0, f.jdbc.rollbacks);
    assertEquals(0, f.calls);
    assertEquals(
        List.of("usage", "settleTokens", "rejectStep", "recordRejection", "rejected", "outcome"),
        f.events);
  }

  @Test
  void scopeRejectionCommitsUsageWithoutResponseBody() {
    var f = new Fixture();
    f.rejectAt = "requireScope";
    assertEquals("TOOL_INPUT_INVALID", assertThrows(TaskFailure.class, f::respond).code());
    assertEquals(1, f.jdbc.commits);
    assertEquals(0, f.jdbc.rollbacks);
    assertFalse(f.events.contains("storeResponse"));
    assertTrue(f.events.contains("rejected"));
    assertEquals(0, f.calls);
  }

  @Test
  void bindingTimeoutRollsBackAndNeverReadsPostCommitOutcome() {
    var f = new Fixture();
    f.timeoutBinding = true;
    assertEquals("SESSION_QUERY_TIMEOUT", assertThrows(TaskFailure.class, f::respond).code());
    assertEquals(0, f.jdbc.commits);
    assertEquals(1, f.jdbc.rollbacks);
    assertFalse(f.events.contains("outcome"));
    assertEquals(7000, f.jdbc.network);
  }

  @Test
  void unknownCommitRetainsTransactionFailureAndSkipsOutcome() {
    var f = new Fixture();
    f.jdbc.commit = new java.sql.SQLException("commit ACK lost", "08006");
    var failure = assertThrows(TransactionSystemException.class, f::respond);
    assertSame(f.jdbc.commit, failure.getCause().getCause());
    assertEquals(1, f.jdbc.commits);
    assertEquals(0, f.jdbc.rollbacks);
    assertFalse(f.events.contains("outcome"));
  }

  @Test
  void corruptProofRollsBackUsageAndCannotBecomeOrdinaryRejection() {
    var f = new Fixture();
    f.rejectAt = "requireScope";
    f.integrityFailure = true;
    assertEquals("SESSION_HISTORY_CORRUPT", assertThrows(TaskFailure.class, f::respond).code());
    assertEquals(0, f.jdbc.commits);
    assertEquals(1, f.jdbc.rollbacks);
    assertFalse(f.events.contains("storeResponse"));
    assertFalse(f.events.contains("rejected"));
    assertFalse(f.events.contains("outcome"));
  }

  private static final class Fixture {
    final SessionQueryTransactionTest.Fixture jdbc = new SessionQueryTransactionTest.Fixture();
    final List<String> events = new ArrayList<>();
    String rejectAt, code;
    boolean timeoutBinding, integrityFailure;
    int calls;
    final ModelCostRecords costs =
        proxy(
            ModelCostRecords.class,
            (object, method, args) -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return switch (method.getName()) {
                case "state" -> "DISPATCHED";
                case "proof" -> {
                  var model =
                      new ModelSnapshot(
                          2,
                          "fixture",
                          1,
                          "adapter",
                          "http://localhost",
                          "model",
                          "revision",
                          "none",
                          null,
                          0,
                          "local",
                          100,
                          100,
                          100,
                          false,
                          100,
                          "max",
                          0,
                          null,
                          Map.of(),
                          false,
                          null,
                          null);
                  yield new ModelCostRecords.Proof(
                      model, "digest", "digest", "digest", null, null, 0);
                }
                case "accountUsage" -> {
                  assertSame(USAGE, args[0]);
                  events.add("usage");
                  yield null;
                }
                case "recordUsage" -> null;
                default -> throw new AssertionError(method.getName());
              };
            });
    final ModelWorkflowRecords.Response row =
        proxy(
            ModelWorkflowRecords.Response.class,
            (object, method, args) -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              String name = method.getName();
              if (name.equals(rejectAt))
                throw new TaskFailure(
                    400, integrityFailure ? "SESSION_HISTORY_CORRUPT" : "TOOL_INPUT_INVALID");
              return switch (name) {
                case "originalDispatch", "constrained" -> true;
                case "question" -> false;
                case "ordinaryRejection" -> !integrityFailure;
                case "settlementOnly" -> null;
                case "costs" -> costs;
                case "reservedTokens", "rejectedPayloadUpper" -> 1000L;
                case "invocationCount" -> 0;
                case "todoRevision" -> 0L;
                case "modelRejection", "runRejection" -> null;
                case "validatePayload", "reserveQuestionStorage", "requireScope" -> null;
                case "insertCall" -> {
                  calls++;
                  assertEquals(calls, args[1]);
                  yield null;
                }
                case "recordRejection" -> {
                  code = (String) args[0];
                  events.add(name);
                  yield null;
                }
                case "bindCalls" -> {
                  events.add(name);
                  if (timeoutBinding) {
                    jdbc.context.restrictDeadline(System.nanoTime() - 1);
                    jdbc.context.remainingMillis();
                  }
                  yield null;
                }
                case "settleTokens", "storeResponse", "rejectStep", "rejected", "responded" -> {
                  events.add(name);
                  yield null;
                }
                default -> throw new AssertionError(name);
              };
            });
    final ModelWorkflowRecords workflows =
        proxy(
            ModelWorkflowRecords.class,
            (object, method, args) -> {
              if (method.getName().equals("lockResponse")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return row;
              }
              assertEquals("outcome", method.getName());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(1, jdbc.commits);
              events.add("outcome");
              return new ModelWorkflowRecords.Outcome("RESPONDED", code);
            });

    void respond() {
      var work =
          new LedgerUnitOfWork() {
            public <T> T required(Supplier<T> body) {
              return jdbc.execute(body);
            }
          };
      new ModelDispatchServiceImpl(null, work, workflows, null, value -> value)
          .responded(LEASE, "step", "dispatch", "response", CALLS, 150, USAGE);
    }
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
