package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.api.TaskController;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskServiceImpl;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/** 从准确基线源码编译旧 HTTP 入口，逐例比较准入顺序、请求快照和响应形状。 */
class TaskAdmissionDifferentialTest {
  @TempDir Path directory;
  private final List<String> calls = new ArrayList<>();
  private final Tasks.Identity identity = new Tasks.Identity("tenant-a", "alice");
  private final Tasks.View view =
      new Tasks.View(
          "00000000-0000-4000-8000-000000000001",
          "QUEUED",
          1,
          Instant.EPOCH,
          Instant.EPOCH,
          null,
          null,
          null,
          List.of());
  private final Tasks records =
      (Tasks)
          Proxy.newProxyInstance(
              Tasks.class.getClassLoader(),
              new Class<?>[] {Tasks.class},
              (proxy, method, args) -> {
                if (!method.getName().equals("submit")) throw new AssertionError(method);
                assertEquals(identity, args[0]);
                calls.add(Payloads.json(args[2]) + ":" + Payloads.json(args[3]));
                return view;
              });
  private final Authenticator auth =
      token -> {
        if (!"trusted".equals(token)) throw new TaskFailure(401, "UNAUTHENTICATED");
        return identity;
      };

  @Test
  void oldAndNewHttpAdmissionHaveIdenticalResultsAndFrozenRequests() throws Exception {
    String source =
        Files.readString(Path.of("src/test/resources/refactoring/TaskController-620d5e0.java.txt"));
    assertEquals(
        "19ba367f8eefe5db864657e4335e5a752d8365ef0865d650999cf07387f43692",
        Payloads.hash(source.getBytes(StandardCharsets.UTF_8)));
    Path file = directory.resolve("BaselineTaskController.java");
    Files.writeString(file, source.replace("TaskController", "BaselineTaskController"));
    int compiled =
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
                file.toString());
    assertEquals(0, compiled);
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
      var baseline =
          loader
              .loadClass("io.github.lovexiaopeng.jimu.api.BaselineTaskController")
              .getConstructor(
                  Tasks.class, Authenticator.class, ArtifactStore.class, EventFeed.class)
              .newInstance(records, auth, null, null);
      var current = new TaskController(new TaskServiceImpl(records), auth, null, null);
      var cases =
          new ArrayList<String>(
              List.of(
                  "null",
                  "{}",
                  "{\"goal\":\"hello\"}",
                  "{\"goal\":\"hello\",\"extra\":true}",
                  "{\"goal\":\"hello\",\"goal\":\"second\"}",
                  "{\"goal\":1}",
                  "{\"goal\":\"hello\",\"images\":[]}",
                  "{\"goal\":\"hello\",\"capabilityIds\":[]}",
                  "{\"goal\":\"hello\",\"capabilityIds\":[null]}",
                  "{\"goal\":\"hello\",\"capabilityIds\":[\"web_fetch\",\"web_fetch\"]}",
                  "{\"goal\":\"hello\",\"capabilityIds\":[\"session_event_search\",\"session_event_read\"]}",
                  "{\"goal\":\"hello\",\"capabilityIds\":[\"session_event_read\",\"session_event_search\"]}",
                  "{\"goal\":\"hello\",\"resourceIds\":[\"untrusted\"]}",
                  "{\"goal\":\"hello\",\"budget\":{\"maxModelRequests\":1.0}}",
                  "{\"goal\":\"hello\",\"budget\":{\"maxModelRequests\":\"1\"}}"));
      for (int n : new int[] {8191, 8192, 8193}) cases.add("{\"goal\":\"" + "x".repeat(n) + "\"}");
      for (int n : new int[] {0, 1, 180, 181})
        cases.add("{\"goal\":\"hello\",\"budget\":{\"deadlineSeconds\":" + n + "}}");
      for (int n : new int[] {0, 1, 8, 9})
        cases.add("{\"goal\":\"hello\",\"budget\":{\"maxModelRequests\":" + n + "}}");
      for (int n : new int[] {0, 1, 20, 21})
        cases.add("{\"goal\":\"hello\",\"budget\":{\"maxToolInvocations\":" + n + "}}");
      for (String input : cases) {
        for (String token : List.of("trusted", "denied")) {
          String old = invoke(baseline, input, token, "key");
          String now = invoke(current, input, token, "key");
          assertEquals(old, now, input + ":" + token);
        }
      }
      for (String key : new String[] {null, "", " ", "x".repeat(128), "x".repeat(129)})
        assertEquals(
            invoke(baseline, "{\"goal\":\"hello\"}", "trusted", key),
            invoke(current, "{\"goal\":\"hello\"}", "trusted", key));
      assertTrue(cases.size() >= 30);
    }
  }

  private String invoke(Object controller, String body, String token, String key) throws Exception {
    calls.clear();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    var input = new ByteArrayInputStream(bytes);
    var stream =
        new ServletInputStream() {
          @Override
          public int read() {
            return input.read();
          }

          @Override
          public boolean isFinished() {
            return input.available() == 0;
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
    var http =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getInputStream")) return stream;
                  throw new AssertionError(method);
                });
    try {
      var response =
          (ResponseEntity<?>)
              controller
                  .getClass()
                  .getMethod("submit", String.class, String.class, HttpServletRequest.class)
                  .invoke(controller, token, key, http);
      return response.getStatusCode()
          + ":"
          + response.getHeaders()
          + ":"
          + new tools.jackson.databind.ObjectMapper().writeValueAsString(response.getBody())
          + ":"
          + calls;
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof TaskFailure rejected)
        return rejected.status() + ":" + rejected.code() + ":" + calls;
      throw failure;
    }
  }
}
