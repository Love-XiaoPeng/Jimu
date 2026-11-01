package io.github.lovexiaopeng.jimu.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 只接管内部submit载体；最终null分派由MVC结束，不再装写listener。 */
final class SessionTaskAsyncReturnValueHandler implements HandlerMethodReturnValueHandler {
  public boolean supportsReturnType(MethodParameter type) {
    return type.getParameterType() == SessionTaskAsyncSubmit.class;
  }

  public void handleReturnValue(
      Object value, MethodParameter type, ModelAndViewContainer container, NativeWebRequest request)
      throws Exception {
    if (value == null) {
      container.setRequestHandled(true);
      return;
    }
    var pump = ((SessionTaskAsyncSubmit) value).pump;
    var manager = WebAsyncUtils.getAsyncManager(request);
    manager.setAsyncWebRequest(new SessionTaskAsyncWebRequest(manager.getAsyncWebRequest(), pump));
    pump.attach(
        java.util.Objects.requireNonNull(request.getNativeResponse(HttpServletResponse.class)));
    manager.registerDeferredResultInterceptor(
        pump,
        new DeferredResultProcessingInterceptor() {
          @Override
          public <T> boolean handleTimeout(NativeWebRequest web, DeferredResult<T> result) {
            pump.terminate(SessionTaskWritePump.timeout());
            return false;
          }

          @Override
          public <T> boolean handleError(
              NativeWebRequest web, DeferredResult<T> result, Throwable failure) {
            pump.terminate(failure);
            return false;
          }

          @Override
          public <T> void afterCompletion(NativeWebRequest web, DeferredResult<T> result) {
            pump.terminate(SessionTaskWritePump.timeout());
          }
        });
    try {
      manager.startDeferredResultProcessing(pump.gate, container);
    } catch (Exception failure) {
      pump.terminate(failure);
      throw failure;
    }
  }
}
