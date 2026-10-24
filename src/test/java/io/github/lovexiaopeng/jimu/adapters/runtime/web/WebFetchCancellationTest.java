package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.CheckpointCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Reason;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class WebFetchCancellationTest {
  @Test
  void firstCauseWinsRacesAndCleanupCannotInventCause() throws Exception {
    for (int i = 0; i < 100; i++) {
      var tracker = new WebFetchCancellation();
      tracker.phase(Phase.BODY);
      var start = new CountDownLatch(1);
      var a =
          Thread.ofPlatform()
              .start(
                  () -> {
                    await(start);
                    tracker.capture(
                        Reason.CHECKPOINT_REJECTED,
                        CheckpointCode.FENCE_REJECTED,
                        System.nanoTime());
                  });
      var b =
          Thread.ofPlatform()
              .start(
                  () -> {
                    await(start);
                    tracker.capture(Reason.CALLER_INTERRUPT, CheckpointCode.NONE, 0);
                  });
      start.countDown();
      a.join();
      b.join();
      var first = tracker.finish();
      assertNotNull(first);
      tracker.phase(Phase.REDIRECT);
      tracker.capture(Reason.UNKNOWN, CheckpointCode.UNKNOWN, 0);
      assertSame(first, tracker.finish());
      assertEquals(Phase.BODY, first.phase());
      assertEquals(
          first.reason() == Reason.CHECKPOINT_REJECTED
              ? CheckpointCode.FENCE_REJECTED
              : CheckpointCode.NONE,
          first.checkpointCode());
    }
    var successful = new WebFetchCancellation();
    assertNull(successful.finish());
    successful.capture(Reason.THREAD_INTERRUPT, CheckpointCode.NONE, 0);
    assertNull(successful.finish());
  }

  @Test
  void checkpointRejectionIsAllowlistedAndPreservesOriginalError() {
    for (String code : List.of("FENCE_REJECTED", "FICTIONAL_ONLY_SECRET_CANARY")) {
      var events = new CopyOnWriteArrayList<WebFetchObservation>();
      var failure = new TaskFailure(409, code);
      var reader =
          new PublicWebReader(
              new PublicWebPolicy(Set.of("example.com")),
              host -> new InetAddress[0],
              (uri, pinned, context, active, progress) -> {
                throw new AssertionError("not admitted");
              });
      assertSame(
          failure,
          assertThrows(
              TaskFailure.class,
              () ->
                  reader.fetch(
                      "https://example.com/",
                      new ExecutionContext(
                          "fictional",
                          Instant.now().plusSeconds(60),
                          () -> {
                            throw failure;
                          }),
                      events::add)));
      var first = events.getLast().cancellation();
      assertEquals(Reason.CHECKPOINT_REJECTED, first.reason());
      assertEquals(
          code.equals("FENCE_REJECTED") ? CheckpointCode.FENCE_REJECTED : CheckpointCode.UNKNOWN,
          first.checkpointCode());
      assertFalse(Payloads.json(events).contains("FICTIONAL_ONLY_SECRET_CANARY"));
    }
  }

  @Test
  void opaqueTransportCancellationIsUnknown() {
    var events = new CopyOnWriteArrayList<WebFetchObservation>();
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host ->
                new InetAddress[] {
                  InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
                },
            (uri, pinned, context, active, progress) -> {
              throw new CancellationException("FICTIONAL_ONLY_SECRET_CANARY");
            });
    assertThrows(
        CancellationException.class,
        () -> reader.fetch("https://example.com/", PublicWebReaderTest.context(), events::add));
    assertEquals(Reason.UNKNOWN, events.getLast().cancellation().reason());
    assertFalse(Payloads.json(events).contains("FICTIONAL_ONLY_SECRET_CANARY"));
  }

  @Test
  void checkedWrapperCannotLoseUnknownCauseDuringCleanup() {
    var events = new CopyOnWriteArrayList<WebFetchObservation>();
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host ->
                new InetAddress[] {
                  InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
                },
            (uri, pinned, context, active, progress) -> {
              throw new IOException(new InterruptedException("FICTIONAL_ONLY_SECRET_CANARY"));
            });
    assertThrows(
        IllegalStateException.class,
        () -> reader.fetch("https://example.com/", PublicWebReaderTest.context(), events::add));
    assertEquals(Reason.UNKNOWN, events.getLast().cancellation().reason());
    assertFalse(Payloads.json(events).contains("FICTIONAL_ONLY_SECRET_CANARY"));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }
}
