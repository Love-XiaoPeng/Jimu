package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
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

/** Container-owned nonblocking body reads: no worker, timer thread or late request writer. */
public final class SessionSearchBodyFilter implements Filter {
  public static final String ATTRIBUTE = "jimu.session.search.request";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof jakarta.servlet.http.HttpServletRequest http
        && http.getRequestURI().endsWith("/session-history/search"))
      ((jakarta.servlet.http.HttpServletResponse) response).setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
    var value = request.getAttribute(ATTRIBUTE);
    if (!(value instanceof SessionEventLedger.SearchRequest search) || !request.isAsyncStarted())
      return;
    request.removeAttribute(ATTRIBUTE);
    var stream = request.getInputStream();
    var reader = new Reader(stream, search);
    request.getAsyncContext().addListener(reader);
    stream.setReadListener(reader);
  }

  private static final class Reader implements ReadListener, AsyncListener {
    private final ServletInputStream stream;
    private final SessionEventLedger.SearchRequest search;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(2049);
    private boolean done;

    Reader(ServletInputStream stream, SessionEventLedger.SearchRequest search) {
      this.stream = stream;
      this.search = search;
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
      while (!done && stream.isReady() && !stream.isFinished()) {
        if (System.nanoTime() >= search.deadlineNanos()) {
          fail(new TaskFailure(504, "SESSION_QUERY_TIMEOUT"));
          return;
        }
        int next = stream.read();
        if (next < 0) break;
        bytes.write(next);
        if (bytes.size() > 2048) {
          fail(new TaskFailure(400, "TOOL_INPUT_INVALID"));
          return;
        }
      }
    }

    @Override
    public void onAllDataRead() {
      byte[] body;
      synchronized (this) {
        if (done) return;
        done = true;
        body = bytes.toByteArray();
        bytes.reset();
      }
      try {
        search.completed().accept(body);
      } catch (RuntimeException failure) {
        search.failed().accept(failure);
      }
    }

    private synchronized void fail(RuntimeException failure) {
      if (done) return;
      done = true;
      bytes.reset();
      search.failed().accept(failure);
    }

    @Override
    public void onError(Throwable failure) {
      fail(new TaskFailure(400, "TOOL_INPUT_INVALID"));
    }

    @Override
    public synchronized void onComplete(AsyncEvent event) {
      done = true;
      bytes.reset();
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      fail(new TaskFailure(504, "SESSION_QUERY_TIMEOUT"));
    }

    @Override
    public void onError(AsyncEvent event) {
      fail(new TaskFailure(400, "TOOL_INPUT_INVALID"));
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }
}
