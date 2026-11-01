package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.session.api.PriorSessionPolicyV4;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionEventSearchPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionTracePolicyV3;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Nonblocking body reads with one shared deadline timer and no request-owned reader thread. */
public final class SessionSearchBodyFilter implements Filter {
  public static final String ATTRIBUTE = "jimu.session.search.request";
  private final java.util.concurrent.ScheduledThreadPoolExecutor deadlines =
      new java.util.concurrent.ScheduledThreadPoolExecutor(
          1,
          runnable -> {
            var thread = new Thread(runnable, "session-search-body-deadline");
            thread.setDaemon(true);
            return thread;
          });

  public SessionSearchBodyFilter() {
    deadlines.setRemoveOnCancelPolicy(true);
  }

  @Override
  public void destroy() {
    deadlines.shutdownNow();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof jakarta.servlet.http.HttpServletRequest http
        && (http.getRequestURI().endsWith("/session-history/search")
            || http.getRequestURI().endsWith("/session-history/trace")
            || http.getRequestURI().endsWith("/session-history/session-search")
            || http.getRequestURI().endsWith("/session-history/session-trace")
            || http.getRequestURI().equals("/api/session-trace/tasks")
            || http.getRequestURI().equals("/api/session-search/tasks")))
      ((jakarta.servlet.http.HttpServletResponse) response).setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
    var value = request.getAttribute(ATTRIBUTE);
    if (!(value instanceof SessionEventLedger.SearchRequest search) || !request.isAsyncStarted())
      return;
    request.removeAttribute(ATTRIBUTE);
    var stream = request.getInputStream();
    Reader reader;
    try {
      reader =
          new Reader(
              stream,
              search,
              ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI().endsWith("/trace")
                  ? SessionTracePolicyV3.INPUT_BYTES
                  : ((jakarta.servlet.http.HttpServletRequest) request)
                          .getRequestURI()
                          .matches("/api/session-(search|trace)/tasks")
                      ? PriorSessionPolicyV4.ADMISSION_BYTES
                      : ((jakarta.servlet.http.HttpServletRequest) request)
                              .getRequestURI()
                              .endsWith("/session-search")
                          ? PriorSessionPolicyV4.INPUT_BYTES
                          : ((jakarta.servlet.http.HttpServletRequest) request)
                                  .getRequestURI()
                                  .endsWith("/session-trace")
                              ? 1024
                              : SessionEventSearchPolicyV1.INPUT_BYTES);
    } catch (RuntimeException failure) {
      search.failed().accept(failure);
      return;
    }
    try {
      request.getAsyncContext().addListener(reader);
      reader.timeout(
          deadlines.schedule(
              () -> reader.fail(new TaskFailure(504, SessionErrorCode.SESSION_QUERY_TIMEOUT)),
              Math.max(0, search.deadlineNanos() - System.nanoTime()),
              java.util.concurrent.TimeUnit.NANOSECONDS));
      stream.setReadListener(reader);
    } catch (RuntimeException failure) {
      reader.fail(failure);
    }
  }

  private static final class Reader implements ReadListener, AsyncListener {
    private final ServletInputStream stream;
    private final SessionEventLedger.SearchRequest search;
    private ByteArrayOutputStream bytes;
    private io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse.InputLease input;
    private final int max;
    private boolean done;
    private boolean bodyDelivered;
    private boolean delivering;
    private java.util.concurrent.ScheduledFuture<?> timeout;

    Reader(ServletInputStream stream, SessionEventLedger.SearchRequest search, int max) {
      this.max = max;
      this.stream = stream;
      this.search = search;
      if (search.inputOwner() != null) input = search.inputOwner().readInput();
      try {
        // 固定max容量，无增长；第max+1字节在write前拒绝，copy最多max。
        bytes = new ByteArrayOutputStream(max);
      } catch (RuntimeException | Error failure) {
        returnInput();
        throw failure;
      }
    }

    private void returnInput() {
      if (delivering || input == null) return;
      input.close();
      input = null;
    }

    synchronized void timeout(java.util.concurrent.ScheduledFuture<?> value) {
      timeout = value;
      if (done) timeout.cancel(false);
    }

    private void stopTimer() {
      if (timeout != null) timeout.cancel(false);
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
      while (!done && stream.isReady() && !stream.isFinished()) {
        if (System.nanoTime() >= search.deadlineNanos()) {
          fail(new TaskFailure(504, SessionErrorCode.SESSION_QUERY_TIMEOUT));
          return;
        }
        int next = stream.read();
        if (next < 0) break;
        if (bytes.size() == max) {
          fail(new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID));
          return;
        }
        bytes.write(next);
      }
    }

    @Override
    public void onAllDataRead() {
      byte[] body;
      synchronized (this) {
        if (done) return;
        // v5 submit沿用最初绝对deadline的同一个timer，直到写周期完成；不另开时钟。
        if (!search.deadlineThroughResponse()) stopTimer();
        try {
          body = bytes.toByteArray();
        } catch (RuntimeException | Error failure) {
          bytes = null;
          done = true;
          stopTimer();
          returnInput();
          throw failure;
        }
        bytes = null;
        done = true;
        bodyDelivered = true;
        delivering = true;
      }
      try {
        search.completed().accept(body);
      } catch (RuntimeException failure) {
        search.failed().accept(failure);
      } finally {
        body = null;
        synchronized (this) {
          delivering = false;
          returnInput();
        }
      }
    }

    private synchronized void fail(RuntimeException failure) {
      if (done && !(bodyDelivered && search.deadlineThroughResponse())) return;
      done = true;
      bodyDelivered = false;
      stopTimer();
      bytes = null;
      try {
        search.failed().accept(failure);
      } finally {
        returnInput();
      }
    }

    @Override
    public void onError(Throwable failure) {
      fail(new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID));
    }

    @Override
    public synchronized void onComplete(AsyncEvent event) {
      done = true;
      bodyDelivered = false;
      stopTimer();
      bytes = null;
      returnInput();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      fail(new TaskFailure(504, SessionErrorCode.SESSION_QUERY_TIMEOUT));
    }

    @Override
    public void onError(AsyncEvent event) {
      fail(new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID));
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }
}
