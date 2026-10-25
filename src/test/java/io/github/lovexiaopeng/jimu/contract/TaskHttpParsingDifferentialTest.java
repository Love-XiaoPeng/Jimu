package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.api.TaskController;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Compare the HTTP adapter with the exact pre-extraction source, without a database or services.
 */
class TaskHttpParsingDifferentialTest {
  private static final String ID = "00000000-0000-4000-8000-00000000000a";
  private static final Tasks.Identity IDENTITY = new Tasks.Identity("tenant", "owner");
  private static final Tasks.View VIEW =
      new Tasks.View(ID, "QUEUED", 1, Instant.EPOCH, Instant.EPOCH, null, null, null, List.of());
  @TempDir static Path directory;
  private static URLClassLoader loader;
  private static Class<?> baseline;
  private final List<Object> calls = new ArrayList<>();
  private TaskFailure getFailure;
  private TaskSubmission submitted;
  private final TaskService tasks =
      (TaskService)
          Proxy.newProxyInstance(
              TaskService.class.getClassLoader(),
              new Class<?>[] {TaskService.class},
              (proxy, method, args) -> {
                assertSame(IDENTITY, args[0]);
                calls.add(Arrays.asList(method.getName(), args[0], args[1]));
                if (method.getName().equals("get")) {
                  if (getFailure != null) throw getFailure;
                } else if (method.getName().equals("submit")) {
                  submitted = (TaskSubmission) args[2];
                  calls.add(submitted);
                } else throw new AssertionError(method);
                return VIEW;
              });
  private final Authenticator auth =
      token -> {
        calls.add("authenticate:" + token);
        if (!"trusted".equals(token)) throw new TaskFailure(401, "UNAUTHENTICATED");
        return IDENTITY;
      };
  private final EventFeed feed =
      (token, id, cursor, event, complete) -> {
        calls.add(List.of("subscribe", token, id, cursor));
        return () -> calls.add("close");
      };

  @BeforeAll
  static void compileExactBaseline() throws Exception {
    Path source = Path.of("src/test/resources/refactoring/TaskController-efba6c7.java.txt");
    byte[] bytes = Files.readAllBytes(source);
    // Git blob SHA1 includes the object header and proves this is the specified Controller blob.
    var hash = java.security.MessageDigest.getInstance("SHA-1");
    hash.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        "4fe66af39d0eb1bff35e698d1db0b024bb47cb10",
        java.util.HexFormat.of().formatHex(hash.digest(bytes)));
    Path file = directory.resolve("HttpBaselineTaskController.java");
    Files.writeString(
        file, Files.readString(source).replace("TaskController", "HttpBaselineTaskController"));
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-encoding",
                "UTF-8",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                file.toString()));
    loader =
        new URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, TaskController.class.getClassLoader());
    baseline = loader.loadClass("io.github.lovexiaopeng.jimu.api.HttpBaselineTaskController");
  }

  @AfterAll
  static void closeLoader() throws Exception {
    if (loader != null) loader.close();
  }

  private Object controller(boolean old) throws Exception {
    return old
        ? baseline
            .getConstructor(
                TaskService.class, Authenticator.class, ArtifactService.class, EventFeed.class)
            .newInstance(tasks, auth, null, feed)
        : new TaskController(tasks, auth, null, feed);
  }

  static Stream<String> bodies() {
    return Stream.of(
        "null",
        "{}",
        "",
        "{",
        "[]",
        "true",
        "{} {}",
        "null {}",
        "{\"goal\":\"hello\",\"unknown\":1}",
        "{\"goal\":\"one\",\"goal\":\"two\"}",
        "{\"goal\":1}",
        "{\"goal\":null,\"capabilityIds\":[null,\"b\",\"b\",\"a\"],\"resourceIds\":[null,\"r\",\"r\"],\"images\":[null]}",
        "{\"goal\":\"hello\"}",
        "{\"goal\":\"hello\",\"budget\":null,\"images\":null}",
        "{\"budget\":{}}",
        "{\"budget\":[]}",
        "{\"budget\":{\"deadlineSeconds\":null,\"maxModelRequests\":null,\"maxToolInvocations\":null,\"maxCostMicros\":null}}",
        "{\"budget\":{\"deadlineSeconds\":-2147483648,\"maxModelRequests\":2147483647,\"maxToolInvocations\":0,\"maxCostMicros\":-9223372036854775808}}",
        "{\"budget\":{\"maxCostMicros\":9223372036854775807}}",
        "{\"budget\":{\"maxCostMicros\":9223372036854775808}}",
        "{\"budget\":{\"deadlineSeconds\":2147483648}}",
        "{\"budget\":{\"deadlineSeconds\":1.0}}",
        "{\"budget\":{\"deadlineSeconds\":\"1\"}}",
        "{\"budget\":{\"deadlineSeconds\":true}}",
        "{\"budget\":{\"maxCostMicros\":1e0}}",
        "{\"budget\":{\"maxCostMicros\":\"1\"}}",
        "{\"budget\":{\"extra\":1}}",
        "{\"budget\":{\"maxCostMicros\":1,\"maxCostMicros\":2}}",
        "{\"capabilityIds\":\"a\"}",
        "{\"resourceIds\":[1]}",
        "{\"images\":[]}",
        "{\"images\":{}}",
        "{\"images\":[1]}",
        "{\"images\":[{\"filePath\":1}]}",
        "{\"images\":[{\"extra\":\"images\"}]}",
        "{\"images\":[{\"filePath\":\"a\",\"dataBase64\":null},null,{\"filePath\":\"a\",\"dataBase64\":\"AA==\"}]}",
        "{\"goal\":\"images\",\"extra\":1}",
        "{\"images\":[],\"images\":[]}");
  }

  @ParameterizedTest
  @MethodSource("bodies")
  void strictRejectionsAndUnnormalizedSubmissionMatch(String body) throws Exception {
    assertEquals(
        submit(true, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "trusted"),
        submit(false, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "trusted"));
  }

  private record Outcome(Object result, List<Object> calls) {}

  private Outcome submit(boolean old, byte[] body, String token) throws Exception {
    calls.clear();
    submitted = null;
    Object result;
    try {
      result = invokeSubmit(controller(old), token, http(body, null));
      var response = (ResponseEntity<?>) result;
      assertEquals(202, response.getStatusCode().value());
      assertEquals("/api/tasks/" + ID, response.getHeaders().getLocation().toString());
      assertSame(VIEW, response.getBody());
      result = List.of(response.getStatusCode(), response.getHeaders(), response.getBody());
      assertEquals(
          4, calls.size()); // authenticate, obtain body, exactly one submit, raw submission
    } catch (TaskFailure failure) {
      result = failure.status() + ":" + failure.code();
    }
    return new Outcome(result, new ArrayList<>(calls));
  }

  @Test
  void authenticateBeforeBodyAndPropagateIoFailure() throws Exception {
    for (boolean old : new boolean[] {true, false}) {
      calls.clear();
      var controller = controller(old);
      IOException io = new IOException("synthetic input failure");
      var denied =
          assertThrows(TaskFailure.class, () -> invokeSubmit(controller, "denied", http(null, io)));
      assertEquals("UNAUTHENTICATED", denied.code());
      assertEquals(List.of("authenticate:denied"), calls);
      calls.clear();
      assertSame(
          io,
          assertThrows(
              IOException.class, () -> invokeSubmit(controller, "trusted", http(null, io))));
      assertEquals(List.of("authenticate:trusted", "body"), calls);
    }
  }

  @Test
  void readsOnlyOriginalLimitPlusOneByte() throws Exception {
    for (int size : new int[] {6291455, 6291456, 6291457, 6291460}) {
      byte[] bytes = new byte[size];
      Arrays.fill(bytes, (byte) ' ');
      byte[] json = "{\"goal\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      System.arraycopy(json, 0, bytes, 0, json.length);
      assertEquals(submit(true, bytes, "trusted"), submit(false, bytes, "trusted"));
      for (boolean old : new boolean[] {true, false}) {
        calls.clear();
        var input = stream(bytes, null);
        try {
          invokeSubmit(controller(old), "trusted", request(input));
        } catch (TaskFailure failure) {
          assertEquals(413, failure.status());
          assertEquals("REQUEST_TOO_LARGE", failure.code());
        }
        assertEquals(Math.max(0, size - 6291457), input.available());
      }
    }
  }

  static Stream<String> cursors() {
    return Stream.of(
        null,
        ID + ":1",
        ID + ":9223372036854775807",
        ID + ":9223372036854775808",
        ID + ":9999999999999999999",
        ID + ":10000000000000000000",
        ID + ":0",
        ID + ":01",
        ID + ":-1",
        ID + ":+1",
        ID + ":1\n",
        ID + ": 1",
        ID + ":１",
        "other:1",
        "",
        ID);
  }

  @ParameterizedTest
  @MethodSource("cursors")
  void cursorValuesAndAuthorizationPrecedenceMatch(String last) throws Exception {
    for (String token : List.of("trusted", "denied")) {
      for (String id : List.of(ID, ID.toUpperCase(java.util.Locale.ROOT), "1-1-1-1-1")) {
        assertEquals(events(true, token, id, last), events(false, token, id, last));
      }
    }
    getFailure = new TaskFailure(404, "NOT_FOUND");
    assertEquals(events(true, "trusted", ID, last), events(false, "trusted", ID, last));
    assertEquals(List.of("authenticate:trusted", Arrays.asList("get", IDENTITY, ID)), calls);
  }

  private Outcome events(boolean old, String token, String id, String last) throws Exception {
    calls.clear();
    Object result;
    try {
      SseEmitter emitter =
          (SseEmitter)
              invoke(
                  controller(old),
                  "events",
                  new Class<?>[] {String.class, String.class, String.class},
                  token,
                  id,
                  last);
      assertEquals(30000L, emitter.getTimeout());
      result = emitter.getTimeout();
    } catch (TaskFailure failure) {
      result = failure.status() + ":" + failure.code();
    }
    return new Outcome(result, new ArrayList<>(calls));
  }

  private Object invokeSubmit(Object controller, String token, HttpServletRequest http)
      throws Exception {
    return invoke(
        controller,
        "submit",
        new Class<?>[] {String.class, String.class, HttpServletRequest.class},
        token,
        null,
        http);
  }

  private Object invoke(Object target, String method, Class<?>[] types, Object... args)
      throws Exception {
    try {
      return target.getClass().getMethod(method, types).invoke(target, args);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    }
  }

  private HttpServletRequest http(byte[] body, IOException failure) {
    return request(stream(body, failure));
  }

  private HttpServletRequest request(ServletInputStream input) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getInputStream")) {
                calls.add("body");
                return input;
              }
              throw new AssertionError(method);
            });
  }

  private ServletInputStream stream(byte[] body, IOException failure) {
    var bytes = new ByteArrayInputStream(body == null ? new byte[0] : body);
    return new ServletInputStream() {
      @Override
      public int read() throws IOException {
        if (failure != null) throw failure;
        return bytes.read();
      }

      @Override
      public int available() {
        return bytes.available();
      }

      @Override
      public boolean isFinished() {
        return bytes.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
