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
    // 独立oracle/外部sink采集是测试设施，不伪装生产计量操作或Servlet全heap证明。
    var captured = new java.io.ByteArrayOutputStream();
    var headers = new java.util.LinkedHashMap<String, String>();
    int[] status = {0};
    String acceptedId = response.taskId();
    byte[] expected;
    try {
      var field = SessionTaskResponse.class.getDeclaredField("view");
      field.setAccessible(true);
      var oracleBytes = new java.io.ByteArrayOutputStream();
      var oracle =
          new org.springframework.http.HttpOutputMessage() {
            private final org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();

            public org.springframework.http.HttpHeaders getHeaders() {
              return headers;
            }

            public java.io.OutputStream getBody() {
              return oracleBytes;
            }
          };
      new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()
          .write(field.get(response), org.springframework.http.MediaType.APPLICATION_JSON, oracle);
      expected = oracleBytes.toByteArray();
    } catch (ReflectiveOperationException | java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    var output =
        new ServletOutputStream() {
          public boolean isReady() {
            return true;
          }

          public void setWriteListener(WriteListener value) {}

          public void write(int value) {
            if (captured.size() == 16384) throw new AssertionError("external test sink cap");
            captured.write(value);
          }
        };
    var http =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                SessionTaskPumpScenario.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (p, m, a) -> {
                  if (m.getName().equals("getOutputStream")) return output;
                  if (m.getName().equals("setStatus")) status[0] = (Integer) a[0];
                  if (m.getName().equals("setHeader")) headers.put((String) a[0], (String) a[1]);
                  return null;
                });
    var pump = new SessionTaskWritePump(deadline, response);
    pump.attach(http);
    pump.produce(() -> {});
    pump.onWritePossible();
    byte[] bytes = captured.toByteArray();
    if (bytes.length > expected.length
        || !java.util.Arrays.equals(bytes, java.util.Arrays.copyOf(expected, bytes.length)))
      throw new AssertionError("production wire differs from Jackson oracle");
    System.out.println("PRODUCTION_PUMP_BYTES " + bytes.length + " state=" + pump.state());
    System.out.println(
        "PRODUCTION_PUMP_EVIDENCE "
            + io.github.lovexiaopeng.jimu.adapters.codec.Payloads.json(
                java.util.Map.ofEntries(
                    java.util.Map.entry("status", status[0]),
                    java.util.Map.entry("headers", headers),
                    java.util.Map.entry(
                        "receivedBase64", java.util.Base64.getEncoder().encodeToString(bytes)),
                    java.util.Map.entry(
                        "expectedBase64", java.util.Base64.getEncoder().encodeToString(expected)),
                    java.util.Map.entry(
                        "receivedSha256",
                        io.github.lovexiaopeng.jimu.adapters.codec.Payloads.hash(bytes)),
                    java.util.Map.entry(
                        "expectedSha256",
                        io.github.lovexiaopeng.jimu.adapters.codec.Payloads.hash(expected)),
                    java.util.Map.entry("completeWire", java.util.Arrays.equals(bytes, expected)),
                    java.util.Map.entry("state", pump.state().name()),
                    java.util.Map.entry("gateHasResult", pump.gate.hasResult()))));
    System.out.flush();
    Object result = pump.gate.getResult();
    if (result instanceof RuntimeException failure) throw failure;
    if (result instanceof Throwable failure) throw new IllegalStateException(failure);
    if (!pump.gate.hasResult())
      throw new AssertionError("uncompleted always-ready production pump");
    if (!java.util.Arrays.equals(bytes, expected)) throw new AssertionError("truncated success");
    if (status[0] != 202
        || !"no-store".equals(headers.get("Cache-Control"))
        || !("/api/tasks/" + acceptedId).equals(headers.get("Location")))
      throw new AssertionError("accepted response headers changed");
  }
}
