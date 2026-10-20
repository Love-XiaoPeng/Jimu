package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublicWebCancellationRaceTest {
  @Test
  void interruptionBeforeResponseCompletionCannotDeliverLateSuccess() throws Exception {
    var receipt = run("interrupt-first", 0);
    assertTrue(receipt.cancelled());
    assertTrue(receipt.callerInterrupted());
    assertTrue(receipt.workerObservedCancellation());
    assertEquals(1, receipt.swallowedInterrupts());
    evidence("interrupt-first", List.of(receipt));
  }

  @Test
  void normalCompletionBeforeInterruptionStillDeliversResponse() throws Exception {
    var receipt = run("complete-first", 0);
    assertFalse(receipt.cancelled());
    assertFalse(receipt.workerObservedCancellation());
    assertEquals(0, receipt.swallowedInterrupts());
    evidence("complete-first", List.of(receipt));
  }

  @Test
  void responseCompletionRacesInterruptionWithoutLosingCancellationOrThreads() throws Exception {
    var receipts = new ArrayList<Receipt>();
    for (int i = 0; i < 100; i++) receipts.add(run("race", i));
    evidence("race", receipts);
  }

  private static Receipt run(String order, int iteration) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var race = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    var callerDone = new AtomicBoolean();
    var callerInterrupted = new AtomicBoolean();
    var workerCancelled = new AtomicBoolean();
    var swallowed = new AtomicInteger();
    var entries = new AtomicInteger();
    var lateEntries = new AtomicInteger();
    var worker = new AtomicReference<Thread>();
    var workerContext = new AtomicReference<ExecutionContext>();
    var outcome = new AtomicReference<Object>();
    var originalDeadline = Instant.now().plusSeconds(20);
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host -> {
              worker.set(Thread.currentThread());
              return new InetAddress[] {
                InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
              };
            },
            (uri, addresses, context, active, progress) -> {
              entries.incrementAndGet();
              if (callerDone.get()) lateEntries.incrementAndGet();
              workerContext.set(context);
              entered.countDown();
              // An already-running transport can also consume interrupts before completing a
              // response.
              while (true) {
                try {
                  if (!release.await(3, TimeUnit.SECONDS))
                    throw new IOException("Fixture release timeout");
                  break;
                } catch (InterruptedException e) {
                  swallowed.incrementAndGet();
                }
              }
              try {
                context.cancellation().throwIfCancelled();
              } catch (CancellationException e) {
                workerCancelled.set(true);
              }
              // Deliberately return success anyway: the reader must still check before delivering
              // it.
              return new PinnedHttpsTransport.Reply(
                  200, null, new FetchOutput(200, uri.toString(), "body", false));
            });
    Thread caller =
        Thread.ofPlatform()
            .name("r4-caller")
            .start(
                () -> {
                  try {
                    outcome.set(
                        reader.fetch(
                            "https://example.com/",
                            new ExecutionContext(
                                "r4-" + order + iteration, originalDeadline, () -> {})));
                  } catch (Throwable failure) {
                    outcome.set(failure);
                  } finally {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                    callerDone.set(true);
                    returned.countDown();
                  }
                });
    Thread interrupter = null;
    Thread completer = null;
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      if (order.equals("interrupt-first")) {
        caller.interrupt();
        assertTrue(returned.await(3, TimeUnit.SECONDS));
        release.countDown();
      } else if (order.equals("complete-first")) {
        release.countDown();
        assertTrue(returned.await(3, TimeUnit.SECONDS));
        caller.interrupt();
      } else {
        interrupter =
            Thread.ofPlatform()
                .name("r4-interrupter")
                .start(
                    () -> {
                      ready.countDown();
                      try {
                        if (race.await(3, TimeUnit.SECONDS)) caller.interrupt();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
        completer =
            Thread.ofPlatform()
                .name("r4-completer")
                .start(
                    () -> {
                      ready.countDown();
                      try {
                        if (race.await(3, TimeUnit.SECONDS)) release.countDown();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        race.countDown();
        assertTrue(returned.await(3, TimeUnit.SECONDS));
      }
    } finally {
      race.countDown();
      release.countDown();
      caller.join(3000);
      if (interrupter != null) interrupter.join(3000);
      if (completer != null) completer.join(3000);
      if (worker.get() != null) worker.get().join(3000);
    }
    assertFalse(caller.isAlive());
    assertTrue(worker.get() != null && !worker.get().isAlive());
    assertTrue(interrupter == null || !interrupter.isAlive());
    assertTrue(completer == null || !completer.isAlive());
    assertTrue(Instant.now().isBefore(originalDeadline), "Deadline must not explain cancellation");
    assertEquals(1, entries.get());
    assertEquals(0, lateEntries.get());
    assertThrows(
        CancellationException.class, () -> workerContext.get().cancellation().throwIfCancelled());
    boolean cancelled = outcome.get() instanceof CancellationException;
    if (!cancelled) assertInstanceOf(FetchOutput.class, outcome.get());
    return new Receipt(
        order,
        iteration,
        cancelled,
        callerInterrupted.get(),
        workerCancelled.get(),
        swallowed.get(),
        entries.get(),
        lateEntries.get(),
        !caller.isAlive(),
        !worker.get().isAlive(),
        interrupter == null || !interrupter.isAlive(),
        completer == null || !completer.isAlive(),
        0);
  }

  private static void evidence(String name, List<Receipt> receipts) throws IOException {
    Path directory = Path.of("target", "r4-evidence");
    Files.createDirectories(directory);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve(name + ".json").toFile(), receipts);
    long cancelled = receipts.stream().filter(Receipt::cancelled).count();
    System.out.println(
        "R4 "
            + name
            + " iterations="
            + receipts.size()
            + " cancelled="
            + cancelled
            + " success="
            + (receipts.size() - cancelled)
            + " lateTransportCalls=0 callerWorkerInterrupterCompleterExited=true realNetworkCalls=0");
  }

  private record Receipt(
      String order,
      int iteration,
      boolean cancelled,
      boolean callerInterrupted,
      boolean workerObservedCancellation,
      int swallowedInterrupts,
      int transportCalls,
      int lateTransportCalls,
      boolean callerExited,
      boolean workerExited,
      boolean interrupterExited,
      boolean completerExited,
      int realNetworkCalls) {}
}
