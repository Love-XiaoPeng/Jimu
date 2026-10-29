package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;

/** 私库同query消费生产pump/cursor；sink替身仅计byte，真实HTTP另行验证。 */
public final class SessionTaskPumpScenario {
  private SessionTaskPumpScenario() {}

  public static void write(SessionTaskResponse response, long deadline) {
    long[] bytes = {0};
    var output =
        new ServletOutputStream() {
          public boolean isReady() {
            return true;
          }

          public void setWriteListener(WriteListener value) {}

          public void write(int value) {
            bytes[0]++;
          }
        };
    var http =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                SessionTaskPumpScenario.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (p, m, a) -> m.getName().equals("getOutputStream") ? output : null);
    var pump = new SessionTaskWritePump(deadline, response);
    pump.attach(http);
    pump.produce(() -> {});
    pump.onWritePossible();
    System.out.println("PRODUCTION_PUMP_BYTES " + bytes[0] + " state=" + pump.state());
    System.out.flush();
    Object result = pump.gate.getResult();
    if (result instanceof RuntimeException failure) throw failure;
    if (result instanceof Throwable failure) throw new IllegalStateException(failure);
    if (!pump.gate.hasResult())
      throw new AssertionError("uncompleted always-ready production pump");
  }
}
