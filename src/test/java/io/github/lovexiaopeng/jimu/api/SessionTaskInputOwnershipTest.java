package io.github.lovexiaopeng.jimu.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 真实Filter/Reader接生产pump；Mock Servlet仅验证内部引用与退款，不代容器全heap。 */
class SessionTaskInputOwnershipTest {
  @Test
  void completedWireStillWaitsForOuterInputCallbackAndReaderErrorReleasesOnce() throws Exception {
    for (boolean error : new boolean[] {false, true}) {
      var h = new SessionTaskNonblockingTest.Harness(true, 0);
      h.response.prepareInput(h.query.deadlineNanos(), 16384);
      h.output.allowance = Integer.MAX_VALUE;
      var input = new Input(16384);
      try (var f =
          new FilterFixture(
              h,
              input,
              body -> {
                h.start(SessionTaskNonblockingTest.view("", null, 0));
                h.pump.onWritePossible();
                assertFalse(h.pump.gate.hasResult());
                assertEquals(0, h.releases);
                assertEquals(16384, body.length);
              })) {
        input.listener.onDataAvailable();
        if (error) input.listener.onError(new IOException("read failed"));
        else input.listener.onAllDataRead();
        assertTrue(h.pump.gate.hasResult());
        assertEquals(error ? 0 : 1, h.output.registrations);
        assertNull(accumulator(input.listener));
        h.released();
      }
    }
  }

  @Test
  void boundedInputDetachesAccumulatorAtNMinusOneNAndRejectsNPlusOne() throws Exception {
    for (int size : new int[] {16383, 16384, 16385}) {
      var h = new SessionTaskNonblockingTest.Harness(true, 0);
      h.response.prepareInput(h.query.deadlineNanos(), 16384);
      var input = new Input(size);
      try (var f =
          new FilterFixture(
              h,
              input,
              body -> {
                assertEquals(size, body.length);
                h.start(SessionTaskNonblockingTest.view("", Instant.EPOCH, 0));
              })) {
        input.listener.onDataAvailable();
        input.listener.onAllDataRead();
        assertNull(accumulator(input.listener));
        if (size <= 16384) {
          assertFalse(h.pump.gate.hasResult());
          assertTrue(h.query.scratchLive() >= 65536 + 33328);
          h.pump.terminate(new IllegalStateException("cancel waiting"));
        }
        assertTrue(h.pump.gate.hasResult());
        h.released();
        input.listener.onDataAvailable();
        input.listener.onAllDataRead();
        input.listener.onError(new IOException("late"));
        h.released();
      }
    }
  }

  @Test
  void cancellationDuringOuterBodyCallbackWaitsForItsLastAlias() throws Exception {
    for (boolean error : new boolean[] {false, true}) {
      var h = new SessionTaskNonblockingTest.Harness(true, 0);
      h.response.prepareInput(h.query.deadlineNanos(), 16384);
      var entered = new CountDownLatch(1);
      var resume = new CountDownLatch(1);
      var input = new Input(16384);
      try (var f =
          new FilterFixture(
              h,
              input,
              body -> {
                h.start(SessionTaskNonblockingTest.view("", null, 0));
                entered.countDown();
                await(resume);
                assertEquals(16384, body.length);
              })) {
        input.listener.onDataAvailable();
        var callback =
            new Thread(
                () -> {
                  try {
                    input.listener.onAllDataRead();
                  } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                  }
                },
                "input-owner-test");
        try {
          callback.start();
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          if (error) input.listener.onError(new IOException("body peer"));
          else h.pump.terminate(SessionTaskWritePump.timeout());
          assertFalse(h.pump.gate.hasResult());
          assertEquals(0, h.releases);
          assertTrue(h.query.scratchLive() > 65536);
          assertNull(accumulator(input.listener));
        } finally {
          resume.countDown();
          callback.join(3000);
        }
        assertFalse(callback.isAlive());
        assertTrue(h.pump.gate.hasResult());
        h.released();
      }
    }
  }

  @Test
  void inputReservationRefusesBeforeReaderAllocationAtScratchBoundary() {
    for (int delta : new int[] {-1, 0, 1}) {
      var h = new SessionTaskNonblockingTest.Harness(true, 0);
      var pressure = new Object();
      h.query.retainScratch(pressure, 1048576 - h.query.scratchLive() - 33328 + delta);
      if (delta > 0)
        assertThrows(
            RuntimeException.class, () -> h.response.prepareInput(h.query.deadlineNanos(), 16384));
      else h.response.prepareInput(h.query.deadlineNanos(), 16384);
      h.query.releaseScratch(pressure);
      h.pump.terminate(SessionTaskWritePump.timeout());
      assertEquals(65536, h.query.scratchLive());
    }
  }

  private static Object accumulator(ReadListener listener) throws Exception {
    var field = listener.getClass().getDeclaredField("bytes");
    field.setAccessible(true);
    return field.get(listener);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("callback not released");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class FilterFixture implements AutoCloseable {
    final SessionSearchBodyFilter filter = new SessionSearchBodyFilter();

    FilterFixture(
        SessionTaskNonblockingTest.Harness h,
        Input input,
        java.util.function.Consumer<byte[]> consumer)
        throws Exception {
      var nativeRequest = new MockHttpServletRequest("POST", "/api/session-trace/tasks");
      nativeRequest.setAsyncSupported(true);
      var request =
          new jakarta.servlet.http.HttpServletRequestWrapper(nativeRequest) {
            public ServletInputStream getInputStream() {
              return input;
            }
          };
      filter.doFilter(
          request,
          h.http,
          (req, res) -> {
            req.setAttribute(
                SessionSearchBodyFilter.ATTRIBUTE,
                new SessionEventLedger.SearchRequest(
                    h.query.deadlineNanos(), consumer, h.pump::terminate, true, h.response));
            req.startAsync();
          });
    }

    public void close() {
      filter.destroy();
    }
  }

  private static final class Input extends ServletInputStream {
    final int size;
    int read;
    ReadListener listener;

    Input(int size) {
      this.size = size;
    }

    public boolean isReady() {
      return true;
    }

    public boolean isFinished() {
      return read == size;
    }

    public int read() {
      return read++ < size ? 'x' : -1;
    }

    public void setReadListener(ReadListener value) {
      listener = value;
    }
  }
}
