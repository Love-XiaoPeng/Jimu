package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class WorkerLoopTest {
  private final OperationThreads operations = new OperationThreads();
  private final AtomicInteger claims = new AtomicInteger();
  private final CountDownLatch quiescent = new CountDownLatch(1);
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final ExecutionLedger ledger =
      (ExecutionLedger)
          Proxy.newProxyInstance(
              ExecutionLedger.class.getClassLoader(),
              new Class<?>[] {ExecutionLedger.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "claim" -> {
                      claims.incrementAndGet();
                      yield Optional.of(
                          new ExecutionLedger.Lease(
                              "tenant",
                              "actor",
                              "task",
                              "attempt",
                              1,
                              "worker",
                              Instant.now().plusSeconds(30),
                              "goal",
                              List.of("web_fetch"),
                              1));
                    }
                    case "quiescent" -> {
                      assertEquals(0, operations.activeCount());
                      quiescent.countDown();
                      yield null;
                    }
                    case "stop" -> {
                      assertEquals("CANCELLED", args[1]);
                      stopped.countDown();
                      yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                  });

  @Test
  void successfulCompletionAcknowledgesOnlyAfterTheActualOperationExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var loop =
        new WorkerLoop(
            ledger,
            lease ->
                operations.start(
                    "late-operation",
                    () -> {
                      entered.countDown();
                      awaitUninterruptibly(release);
                    }),
            "host",
            () -> true,
            operations);
    var execution = Thread.ofPlatform().start(loop::tick);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(1, operations.activeCount());
      assertFalse(quiescent.await(100, TimeUnit.MILLISECONDS));
      loop.tick();
      assertEquals(1, claims.get(), "No other attempt while a child operation remains live");
      release.countDown();
      execution.join(2000);
      assertFalse(execution.isAlive());
      assertEquals(0, quiescent.getCount());
    } finally {
      release.countDown();
      execution.join(2000);
      loop.close();
    }
  }

  @Test
  void cancelledExecutionRetainsTheAttemptUntilItsUninterruptibleChildExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var loop =
        new WorkerLoop(
            ledger,
            lease -> {
              operations.start(
                  "cancelled-child",
                  () -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                  });
              throw new TaskFailure(409, "CANCELLED");
            },
            "host",
            () -> true,
            operations);
    var execution = Thread.ofPlatform().start(loop::tick);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(stopped.await(2, TimeUnit.SECONDS));
      assertFalse(quiescent.await(100, TimeUnit.MILLISECONDS));
      assertEquals(1, operations.activeCount());
      loop.tick();
      assertEquals(1, claims.get());
      release.countDown();
      execution.join(2000);
      assertFalse(execution.isAlive());
      assertEquals(0, quiescent.getCount());
    } finally {
      release.countDown();
      execution.join(2000);
      loop.close();
    }
  }

  @Test
  void interruptedDrainNeverAcknowledgesQuiescenceOrClaimsAnotherAttempt() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var loop =
        new WorkerLoop(
            ledger,
            lease ->
                operations.start(
                    "uninterruptible-operation",
                    () -> {
                      entered.countDown();
                      awaitUninterruptibly(release);
                    }),
            "host",
            () -> true,
            operations);
    var execution = Thread.ofPlatform().start(loop::tick);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      execution.interrupt();
      execution.join(2000);
      assertFalse(execution.isAlive());
      assertEquals(1, quiescent.getCount());
      assertEquals(1, operations.activeCount());
      release.countDown();
      operations.awaitDrained();
      loop.tick();
      assertEquals(
          1, claims.get(), "Only supervisor process exit can resolve an interrupted drain");
      assertEquals(1, quiescent.getCount());
    } finally {
      release.countDown();
      execution.join(2000);
      operations.awaitDrained();
      assertThrows(IllegalStateException.class, loop::close);
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    while (true) {
      try {
        latch.await();
        return;
      } catch (InterruptedException ignored) {
        // Simulate platform DNS, which may consume interruption and finish later.
      }
    }
  }
}
