package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Original five substitutes, using injected clocks and transport without network or database. */
public final class CancelBoundaryDiagnosticTest {
  static final List<Map<String, Object>> evidence = new ArrayList<>();

  static void require(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }

  static void run(String scenario) throws Exception {
    var operations = new OperationThreads();
    var observations = new CopyOnWriteArrayList<WebFetchObservation>();
    var entered = new CountDownLatch(1);
    var result = new AtomicReference<Object>();
    var at = Instant.now();
    // Future wall admission isolates the unmodified 30-second monotonic budget.
    var clock =
        scenario.equals("ELAPSED_30S")
            ? Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC)
            : scenario.equals("ABSOLUTE_FETCH_DEADLINE")
                ? Clock.fixed(at.minusMillis(29000), ZoneOffset.UTC)
                : Clock.systemUTC();
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host ->
                new InetAddress[] {
                  InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
                },
            (uri, pinned, context, active, progress) -> {
              progress.accept(WebFetchObservation.Phase.HEADERS);
              progress.accept(WebFetchObservation.Phase.BODY);
              entered.countDown();
              if (!scenario.equals("SUCCESS")) {
                while (true) {
                  context.cancellation().throwIfCancelled();
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException ignored) {
                    context.cancellation().throwIfCancelled();
                  }
                }
              }
              progress.accept(WebFetchObservation.Phase.PROJECTION);
              return new PinnedHttpsTransport.Reply(
                  200, null, new FetchOutput(200, uri.toString(), "fictional local body", false));
            },
            operations,
            clock);
    var original =
        new ExecutionContext(
            "fictional-" + scenario,
            at.plusSeconds(120),
            () -> {
              if (scenario.equals("UPSTREAM_CANCELLATION") && entered.getCount() == 0)
                throw new CancellationException("FICTIONAL_UPSTREAM_CANCEL");
            });
    long started = System.nanoTime();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    result.set(reader.fetch("https://example.com/", original, observations::add));
                  } catch (Throwable failure) {
                    result.set(failure);
                  }
                });
    require(entered.await(2, TimeUnit.SECONDS), "BODY_BARRIER");
    if (scenario.equals("CALLER_INTERRUPT")) caller.interrupt();
    caller.join(35000);
    if (caller.isAlive()) {
      caller.interrupt();
      caller.join(2000);
      throw new AssertionError("CALLER_TIMEOUT");
    }
    operations.awaitDrained();
    long elapsed = (System.nanoTime() - started) / 1000000;
    var last = observations.getLast();
    if (scenario.equals("SUCCESS")) {
      require(result.get() instanceof FetchOutput, "SUCCESS_OUTPUT");
      require(last.failure() == WebFetchObservation.Failure.NONE, "SUCCESS_NO_FAILURE");
    } else {
      require(result.get() instanceof CancellationException, "CANCELLATION_TYPE");
      require(
          last.phase() == WebFetchObservation.Phase.BODY
              && last.failure() == WebFetchObservation.Failure.CANCELLED,
          "BODY_CANCELLED");
      require(last.cancellation() != null, "FIRST_CAUSE_PRESENT");
      if (scenario.equals("ELAPSED_30S"))
        require(
            last.cancellation().reason() == WebFetchObservation.Reason.ELAPSED_LIMIT,
            "MONOTONIC_REASON");
      if (scenario.equals("CALLER_INTERRUPT"))
        require(
            last.cancellation().reason() == WebFetchObservation.Reason.CALLER_INTERRUPT,
            "CALLER_REASON");
      if (scenario.equals("UPSTREAM_CANCELLATION"))
        require(
            last.cancellation().reason() == WebFetchObservation.Reason.CHECKPOINT_REJECTED,
            "CALLBACK_REASON");
    }
    if (scenario.equals("ELAPSED_30S"))
      require(elapsed >= 29000 && elapsed < 35000, "ELAPSED_BOUND");
    if (scenario.equals("ABSOLUTE_FETCH_DEADLINE")) require(elapsed < 5000, "ABSOLUTE_BOUND");
    require(operations.activeCount() == 0, "DRAINED");
    require(Instant.now().isBefore(original.deadline()), "ORIGINAL_TASK_DEADLINE_NOT_REACHED");
    evidence.add(
        Map.of(
            "scenario",
            scenario,
            "elapsedMillis",
            elapsed,
            "lastPhase",
            last.phase().name(),
            "lastFailure",
            last.failure().name(),
            "cancellation",
            last.cancellation() == null ? "NONE" : last.cancellation().toString(),
            "resultClass",
            result.get().getClass().getSimpleName(),
            "activeOperationsAfter",
            operations.activeCount(),
            "originalDeadlineReached",
            false,
            "networkCalls",
            0));
  }

  @org.junit.jupiter.api.Test
  void fiveOriginalSubstitutes() throws Exception {
    for (var scenario :
        List.of(
            "ELAPSED_30S",
            "ABSOLUTE_FETCH_DEADLINE",
            "CALLER_INTERRUPT",
            "UPSTREAM_CANCELLATION",
            "SUCCESS")) run(scenario);
    System.out.println(
        new ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "status",
                    "PASSED",
                    "productClassesUnmodified",
                    false,
                    "cases",
                    evidence,
                    "externalRequests",
                    0,
                    "databaseConnections",
                    0)));
  }
}
