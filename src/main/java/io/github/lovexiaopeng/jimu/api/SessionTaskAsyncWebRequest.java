package io.github.lovexiaopeng.jimu.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Consumer;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncWebRequest;

/** Spring7的DeferredResult生命周期错误处理先于用户interceptor；在此窄入口延后其默认结束。 */
final class SessionTaskAsyncWebRequest extends ServletWebRequest implements AsyncWebRequest {
  private final AsyncWebRequest delegate;
  private final SessionTaskWritePump pump;

  SessionTaskAsyncWebRequest(AsyncWebRequest delegate, SessionTaskWritePump pump) {
    super(
        java.util.Objects.requireNonNull(delegate.getNativeRequest(HttpServletRequest.class)),
        java.util.Objects.requireNonNull(delegate.getNativeResponse(HttpServletResponse.class)));
    this.delegate = delegate;
    this.pump = pump;
  }

  public void setTimeout(Long timeout) {
    delegate.setTimeout(timeout);
  }

  public void addTimeoutHandler(Runnable callback) {
    delegate.addTimeoutHandler(callback);
  }

  public void addErrorHandler(Consumer<Throwable> callback) {
    // 不让DeferredResult内置setResultInternal在借用归还之前抢先dispatch。
    delegate.addErrorHandler(pump::terminate);
  }

  public void addCompletionHandler(Runnable callback) {
    delegate.addCompletionHandler(callback);
  }

  public void startAsync() {
    delegate.startAsync();
  }

  public boolean isAsyncStarted() {
    return delegate.isAsyncStarted();
  }

  public void dispatch() {
    delegate.dispatch();
  }

  public boolean isAsyncComplete() {
    return delegate.isAsyncComplete();
  }
}
