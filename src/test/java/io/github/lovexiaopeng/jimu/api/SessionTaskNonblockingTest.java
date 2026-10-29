package io.github.lovexiaopeng.jimu.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletResponse;

/** 生产cursor/pump和当前Jackson逐byte对照；受控Servlet背压，不冒称网络R1/R2。 */
class SessionTaskNonblockingTest {
  static Tasks.View view(String text, Instant time, long number) {
    return new Tasks.View(
        "caller",
        "QUEUED",
        number,
        time,
        time,
        null,
        text,
        text,
        List.of(
            new Tasks.Artifact(text, "text/plain", number, "hash", time),
            new Tasks.Artifact(null, null, 0, null, null)));
  }

  private static byte[] oracle(Tasks.View value) throws Exception {
    var output = new MockHttpOutputMessage();
    new JacksonJsonHttpMessageConverter().write(value, MediaType.APPLICATION_JSON, output);
    return output.getBodyAsBytes();
  }

  @Test
  void cursorMatchesJacksonAcrossAllUtf16BlocksAndExtremes() throws Exception {
    for (int base = 0; base < 65536; base += 128) {
      var text = new StringBuilder();
      for (int i = base; i < base + 128; i++) text.append((char) i);
      same(view(text.toString(), Instant.EPOCH, Long.MIN_VALUE));
    }
    for (Instant time :
        List.of(
            Instant.MIN,
            Instant.MAX,
            Instant.parse("0000-02-29T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59.001Z"),
            Instant.parse("+10000-01-01T00:00:00.000001Z"),
            Instant.parse("1900-03-01T12:34:56.123456789Z"),
            Instant.parse("2000-02-29T23:59:59.123456Z")))
      for (long number : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE})
        same(view("\"\\\n中文😀\uD800x\uDC00\uD800\uD800", time, number));
    same(new Tasks.View(null, null, 0, null, null, null, null, null, List.of()));
  }

  private static void same(Tasks.View value) throws Exception {
    var h = new Harness(false, 0);
    h.response.hold(value);
    h.response.produced();
    var bytes = new ByteArrayOutputStream();
    try (var lease = h.response.consume(SessionTaskJsonCursor.WORKSPACE_BYTES);
        var cursor = new SessionTaskJsonCursor(h.response, lease.view())) {
      for (int next; (next = cursor.next()) >= 0; ) bytes.write(next);
    }
    assertArrayEquals(oracle(value), bytes.toByteArray());
    h.released();
  }

  @Test
  void unreadyReturnsAndOneByteResumesNeverReencodeOrResend() throws Exception {
    var h = new Harness(true, 0);
    var value = view("字😀\n".repeat(18), Instant.EPOCH, 123);
    h.start(value);
    h.pump.onWritePossible();
    assertFalse(h.pump.gate.hasResult());
    assertEquals(0, h.output.bytes.size());
    assertNull(h.query.completedWork().get("response.encode"));
    for (int i = 0; !h.pump.gate.hasResult() && i < 3000; i++) {
      h.output.allowance = 1;
      h.pump.onWritePossible();
    }
    assertTrue(h.pump.gate.hasResult());
    assertNull(h.pump.gate.getResult());
    assertEquals(1, h.output.registrations);
    assertEquals(202, h.http.getStatus());
    assertEquals("/api/tasks/caller", h.http.getHeader("Location"));
    assertArrayEquals(oracle(value), h.output.bytes.toByteArray());
    var uninterrupted = new Harness(true, 0);
    uninterrupted.output.allowance = Integer.MAX_VALUE;
    uninterrupted.start(value);
    uninterrupted.pump.onWritePossible();
    assertEquals(
        uninterrupted.query.completedWork().get("response.encode"),
        h.query.completedWork().get("response.encode"));
    assertEquals(
        uninterrupted.query.completedWork().get("response.copy"),
        h.query.completedWork().get("response.copy"));
    uninterrupted.released();
    assertEquals((long) h.output.bytes.size(), h.query.completedWork().get("response.wire"));
    h.released();
    h.pump.onWritePossible();
    h.pump.onError(new IOException("late"));
    h.released();
  }

  @Test
  void peerFailureCompletesSeventeenOfEighteenRequestedBytes() throws Exception {
    var h = new Harness(true, 0);
    h.output.allowance = Integer.MAX_VALUE;
    h.output.failAt = 17;
    h.start(view("", null, 0));
    h.pump.onWritePossible();
    assertEquals(17, h.output.bytes.size());
    assertEquals(18L, h.query.requestedWork().get("response.wire"));
    assertEquals(17L, h.query.completedWork().get("response.wire"));
    assertSame(h.query.recordedFailure(), h.pump.gate.getResult());
    h.released();
  }

  @Test
  void registrationSinkProducerAndLateFailuresCloseOnce() throws Exception {
    for (String mode : List.of("registration", "sink", "producer", "late")) {
      var h = new Harness(true, 0);
      h.output.registrationFailure = mode.equals("registration");
      h.sinkFailure = mode.equals("sink");
      var marker = new IllegalStateException("producer marker");
      if (mode.equals("late")) h.pump.terminate(marker);
      h.pump.produce(
          () -> {
            if (mode.equals("late")) throw new AssertionError("late producer ran");
            if (mode.equals("producer")) throw marker;
            h.response.hold(view("", null, 0));
          });
      assertTrue(h.pump.gate.hasResult(), mode);
      assertNotNull(h.pump.gate.getResult());
      assertEquals(0, h.output.bytes.size());
      h.released();
    }
  }

  @Test
  void cancelUnreadyNeverWrites() throws Exception {
    var h = new Harness(true, 0);
    h.start(view("", null, 0));
    h.pump.terminate(SessionTaskWritePump.timeout());
    assertTrue(h.pump.gate.hasResult());
    assertEquals(0, h.output.bytes.size());
    h.released();
  }

  @Test
  void scratchBoundaryReservesWorkspaceBeforeSinkOrListenerAllocation() throws Exception {
    for (int delta : new int[] {-1, 0, 1}) {
      var h = new Harness(true, 0);
      Object pressure = new Object();
      h.query.retainScratch(
          pressure,
          1048576 - h.query.scratchLive() - SessionTaskJsonCursor.WORKSPACE_BYTES - 128 + delta);
      h.start(view("", null, 0));
      assertEquals(delta > 0 ? 0 : 1, h.output.registrations);
      if (delta > 0) assertSame(h.query.recordedFailure(), h.pump.gate.getResult());
      else h.pump.terminate(SessionTaskWritePump.timeout());
      h.query.releaseScratch(pressure);
      h.released();
    }
  }

  @Test
  void originalBodyTimerRemainsActiveWhileResponseSinkNeverBecomesReady() throws Exception {
    var h = new Harness(true, 0);
    long deadline = System.nanoTime() + 100_000_000L;
    h.query.restrictDeadline(deadline);
    var expired = new CountDownLatch(1);
    var input =
        new jakarta.servlet.ServletInputStream() {
          int offset;

          public boolean isReady() {
            return true;
          }

          public boolean isFinished() {
            return offset == 2;
          }

          public int read() {
            return offset++ == 0 ? '{' : '}';
          }

          public void setReadListener(jakarta.servlet.ReadListener reader) {
            try {
              reader.onDataAvailable();
              reader.onAllDataRead();
            } catch (IOException failure) {
              throw new java.io.UncheckedIOException(failure);
            }
          }
        };
    var nativeRequest =
        new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/session-trace/tasks");
    nativeRequest.setAsyncSupported(true);
    var request =
        new jakarta.servlet.http.HttpServletRequestWrapper(nativeRequest) {
          public jakarta.servlet.ServletInputStream getInputStream() {
            return input;
          }
        };
    var filter = new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter();
    try {
      filter.doFilter(
          request,
          h.http,
          (req, res) -> {
            req.setAttribute(
                "jimu.session.search.request",
                new io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger.SearchRequest(
                    deadline,
                    body -> h.start(view("", null, 0)),
                    failure -> {
                      h.pump.terminate(failure);
                      expired.countDown();
                    },
                    true));
            req.startAsync();
          });
      assertFalse(h.pump.gate.hasResult());
      assertTrue(expired.await(2, TimeUnit.SECONDS));
      assertTrue(h.pump.gate.hasResult());
      assertEquals(0, h.output.bytes.size());
      h.released();
    } finally {
      filter.destroy();
    }
  }

  @Test
  void cancelOrErrorInFlightWaitsForBorrowAndDoesNotRefundOrDispatchEarly() throws Exception {
    for (boolean error : new boolean[] {false, true}) {
      var h = new Harness(true, 0);
      h.start(view("", null, 0));
      h.output.allowance = Integer.MAX_VALUE;
      h.output.entered = new CountDownLatch(1);
      h.output.unblock = new CountDownLatch(1);
      var thread = new Thread(h.pump::onWritePossible);
      try {
        thread.start();
        assertTrue(h.output.entered.await(2, TimeUnit.SECONDS));
        if (error) h.pump.onError(new IOException("async peer error"));
        else h.pump.terminate(SessionTaskWritePump.timeout());
        assertFalse(h.pump.gate.hasResult());
        assertEquals(0, h.releases);
        assertTrue(h.query.scratchLive() > 65536);
        h.output.unblock.countDown();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertEquals(1, h.output.bytes.size());
        assertEquals(1L, h.query.completedWork().get("response.wire"));
        assertTrue(h.pump.gate.hasResult());
        h.released();
      } finally {
        h.output.unblock.countDown();
        thread.join(6000);
        assertFalse(thread.isAlive());
      }
    }
  }

  @Test
  void producerTimeoutWaitsForReturnAndClearsLateView() throws Exception {
    var h = new Harness(true, 0);
    var entered = new CountDownLatch(1);
    var unblock = new CountDownLatch(1);
    var thread =
        new Thread(
            () ->
                h.pump.produce(
                    () -> {
                      h.response.hold(view("", null, 0));
                      entered.countDown();
                      await(unblock);
                    }));
    try {
      thread.start();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      h.pump.terminate(SessionTaskWritePump.timeout());
      assertEquals(0, h.releases);
      assertFalse(h.pump.gate.hasResult());
      unblock.countDown();
      thread.join(2000);
      assertFalse(thread.isAlive());
      assertEquals(0, h.output.registrations);
      h.released();
    } finally {
      unblock.countDown();
      thread.join(6000);
      assertFalse(thread.isAlive());
    }
  }

  @Test
  void exactOperationBoundaryRejectsNextPrimitiveAndKeepsFirstSlot() throws Exception {
    var sample = new Harness(true, 0);
    sample.output.allowance = Integer.MAX_VALUE;
    var value = view("", null, 0);
    sample.start(value);
    sample.pump.onWritePossible();
    long cost = sample.query.resources().operations();
    sample.released();
    for (int delta : new int[] {-1, 0, 1}) {
      var h = new Harness(true, 65536 - cost + delta);
      h.output.allowance = Integer.MAX_VALUE;
      h.start(value);
      h.pump.onWritePossible();
      assertEquals(delta > 0, h.pump.gate.getResult() != null);
      if (delta > 0) {
        assertSame(h.query.recordedFailure(), h.pump.gate.getResult());
        assertEquals(65537, h.query.resources().operations());
      }
      h.released();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("unreleased fixture");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  static final class Output extends ServletOutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int allowance, registrations, failAt = -1;
    boolean registrationFailure;
    CountDownLatch entered, unblock;
    WriteListener listener;

    public boolean isReady() {
      return allowance > 0;
    }

    public void setWriteListener(WriteListener value) {
      registrations++;
      if (registrationFailure) throw new IllegalStateException("registration marker");
      listener = value;
    }

    public void write(int value) throws IOException {
      if (entered != null) {
        entered.countDown();
        await(unblock);
      }
      if (bytes.size() == failAt) throw new IOException("peer marker");
      if (allowance <= 0) throw new AssertionError("write while not ready");
      bytes.write(value);
      allowance--;
    }
  }

  static final class Harness {
    final SessionQueryContext query;
    final SessionTaskResponse response = new SessionTaskResponse();
    final Output output = new Output();
    final SessionTaskWritePump pump;
    int releases;
    boolean sinkFailure;
    final MockHttpServletResponse http =
        new MockHttpServletResponse() {
          public ServletOutputStream getOutputStream() {
            if (sinkFailure) throw new IllegalStateException("sink marker");
            return output;
          }
        };

    Harness(boolean metered, long prior) {
      long deadline = System.nanoTime() + 2_000_000_000L;
      query = new SessionQueryContext(deadline, metered ? new QueryResources(0, 0, prior) : null);
      query.restrictLineage();
      response.bind(
          new SessionTaskResponse.Budget() {
            public void retain(Object owner, long bytes) {
              query.retainScratch(owner, bytes);
            }

            public void release(Object owner) {
              releases++;
              query.releaseScratch(owner);
            }

            public void wire(int count) {
              query.operation(count);
            }

            public void checkpoint() {
              query.remainingMillis();
            }

            public RuntimeException failure(RuntimeException cause) {
              return query.recordFailure(cause);
            }

            public <T> T primitive(String name, int count, java.util.function.Supplier<T> action) {
              return query.primitive(name, count, action);
            }
          });
      response.prepare(deadline, 8192);
      pump = new SessionTaskWritePump(deadline, response);
      pump.attach(http);
    }

    void start(Tasks.View value) {
      pump.produce(() -> response.hold(value));
    }

    void released() throws Exception {
      assertEquals(1, releases);
      assertEquals(65536, query.scratchLive());
      for (String name : List.of("view", "budget")) {
        var f = SessionTaskResponse.class.getDeclaredField(name);
        f.setAccessible(true);
        assertNull(f.get(response));
      }
      if (pump.state() == SessionTaskWritePump.State.TERMINATED)
        for (String name : List.of("response", "lease", "cursor", "chunk", "sink", "http")) {
          var f = SessionTaskWritePump.class.getDeclaredField(name);
          f.setAccessible(true);
          assertNull(f.get(pump));
        }
    }
  }
}
