package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.api.TaskController;
import io.github.lovexiaopeng.jimu.api.task.vo.TaskEventResponse;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** 编译准确旧 Controller，比较下载和模拟 feed 的表示/回调；不冒充网络或数据库验收。 */
class TaskResponseDifferentialTest {
  private static final String ID = "00000000-0000-4000-8000-00000000000a";
  private static final Tasks.Identity IDENTITY = new Tasks.Identity("tenant", "owner");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir static Path directory;
  private static URLClassLoader loader;
  private static Class<?> baseline;
  private final List<Object> calls = new ArrayList<>();
  private ArtifactService.Content content;
  private TaskFailure ownerFailure;
  private Consumer<Tasks.Event> eventCallback;
  private Runnable completeCallback;
  private final Authenticator auth =
      token -> {
        calls.add("authenticate:" + token);
        if (!"trusted".equals(token)) throw new TaskFailure(401, "UNAUTHENTICATED");
        return IDENTITY;
      };
  private final ArtifactService artifacts =
      (identity, id) -> {
        assertSame(IDENTITY, identity);
        calls.add(List.of("download", identity, id));
        if (ownerFailure != null) throw ownerFailure;
        return content;
      };
  private final TaskService tasks =
      (TaskService)
          Proxy.newProxyInstance(
              TaskService.class.getClassLoader(),
              new Class<?>[] {TaskService.class},
              (proxy, method, args) -> {
                assertEquals("get", method.getName());
                calls.add(Arrays.asList("get", args[0], args[1]));
                return null;
              });
  private final EventFeed feed =
      (token, id, cursor, event, complete) -> {
        calls.add(List.of("subscribe", token, id, cursor));
        eventCallback = event;
        completeCallback = complete;
        return () -> calls.add("close");
      };

  @BeforeAll
  static void compileExactBaseline() throws Exception {
    Path source = Path.of("src/test/resources/refactoring/TaskController-8574f7f.java.txt");
    byte[] bytes = Files.readAllBytes(source);
    var hash = java.security.MessageDigest.getInstance("SHA-1");
    hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "12955edc6aec02df921d94c1a2f38f10722d2c5e",
        java.util.HexFormat.of().formatHex(hash.digest(bytes)));
    Path file = directory.resolve("ResponseBaselineTaskController.java");
    Files.writeString(
        file, Files.readString(source).replace("TaskController", "ResponseBaselineTaskController"));
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-g",
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
    baseline = loader.loadClass("io.github.lovexiaopeng.jimu.api.ResponseBaselineTaskController");
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
            .newInstance(tasks, auth, artifacts, feed)
        : new TaskController(tasks, auth, artifacts, feed);
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

  private ResponseEntity<?> download(boolean old, String token, String id) throws Exception {
    return (ResponseEntity<?>)
        invoke(controller(old), "download", new Class<?>[] {String.class, String.class}, token, id);
  }

  static Stream<String> mediaTypes() {
    return Stream.of(
        "text/markdown; charset=utf-8",
        "text/markdown;charset=utf-8",
        "text/markdown; charset=UTF-8",
        "TEXT/MARKDOWN; charset=utf-8",
        "text/plain",
        "application/octet-stream");
  }

  @ParameterizedTest
  @MethodSource("mediaTypes")
  void downloadHeadersAndOriginalBytesMatch(String media) throws Exception {
    for (byte[] bytes :
        List.of(new byte[0], new byte[] {0, -1, 1, 10}, "内容".getBytes(StandardCharsets.UTF_8))) {
      content =
          new ArtifactService.Content(new Tasks.Artifact(ID, media, 999, "abcdef", null), bytes);
      calls.clear();
      var old = download(true, "trusted", ID);
      var oldCalls = new ArrayList<>(calls);
      calls.clear();
      var current = download(false, "trusted", ID);
      assertEquals(oldCalls, calls);
      assertEquals(List.of("authenticate:trusted", List.of("download", IDENTITY, ID)), calls);
      assertEquals(200, current.getStatusCode().value());
      assertEquals(old.getHeaders(), current.getHeaders());
      assertSame(bytes, old.getBody());
      assertSame(bytes, current.getBody());
      assertEquals(bytes.length, current.getHeaders().getContentLength());
      assertEquals(
          media.equals("text/markdown; charset=utf-8")
              ? "attachment; filename=\"" + ID + ".md\""
              : "attachment; filename=\"" + ID + ".txt\"",
          current.getHeaders().getFirst("Content-Disposition"));
      assertEquals("nosniff", current.getHeaders().getFirst("X-Content-Type-Options"));
      assertEquals("\"abcdef\"", current.getHeaders().getETag());
    }
  }

  @Test
  void downloadRejectionsPrecedeAnyResponseMapping() throws Exception {
    // A null result would fail response assembly if any rejected request reached it.
    content = null;
    for (boolean old : new boolean[] {true, false}) {
      calls.clear();
      assertEquals(
          "UNAUTHENTICATED",
          assertThrows(TaskFailure.class, () -> download(old, "denied", "invalid")).code());
      assertEquals(List.of("authenticate:denied"), calls);
      for (String id : List.of("invalid", ID.toUpperCase(java.util.Locale.ROOT), "1-1-1-1-1")) {
        calls.clear();
        assertEquals(
            "NOT_FOUND",
            assertThrows(TaskFailure.class, () -> download(old, "trusted", id)).code());
        assertEquals(List.of("authenticate:trusted"), calls);
      }
      ownerFailure = new TaskFailure(404, "NOT_FOUND");
      calls.clear();
      assertSame(ownerFailure, assertThrows(TaskFailure.class, () -> download(old, "trusted", ID)));
      assertEquals(List.of("authenticate:trusted", List.of("download", IDENTITY, ID)), calls);
      ownerFailure = null;
    }
  }

  record InvalidContent(ArtifactService.Content value, Class<? extends Exception> exception) {}

  static Stream<InvalidContent> invalidContents() {
    return Stream.of(
        new InvalidContent(null, NullPointerException.class),
        new InvalidContent(new ArtifactService.Content(null, null), NullPointerException.class),
        new InvalidContent(
            new ArtifactService.Content(new Tasks.Artifact(ID, "invalid", 0, null, null), null),
            org.springframework.http.InvalidMediaTypeException.class),
        new InvalidContent(
            new ArtifactService.Content(new Tasks.Artifact(ID, null, 0, null, null), null),
            org.springframework.http.InvalidMediaTypeException.class),
        new InvalidContent(
            new ArtifactService.Content(new Tasks.Artifact(ID, "text/plain", 0, null, null), null),
            NullPointerException.class));
  }

  @ParameterizedTest
  @MethodSource("invalidContents")
  void invalidMediaAndNullExceptionPrecedenceMatch(InvalidContent input) throws Exception {
    content = input.value();
    Exception before = assertThrows(input.exception(), () -> download(true, "trusted", ID));
    Exception after = assertThrows(input.exception(), () -> download(false, "trusted", ID));
    assertEquals(before.getMessage(), after.getMessage());
  }

  static Stream<Tasks.Event> events() {
    return Stream.of(
        new Tasks.Event(
            ID + ":3",
            3,
            "task.updated",
            ID,
            Instant.parse("2026-10-25T07:00:00.123456789Z"),
            "CODE",
            "SUCCEEDED",
            List.of("b", "a", "b")),
        new Tasks.Event(
            ID + ":9223372036854775807",
            Long.MAX_VALUE,
            "task.updated",
            null,
            null,
            null,
            null,
            List.of()));
  }

  @ParameterizedTest
  @MethodSource("events")
  void eventFieldsJsonAndSseMappingMatch(Tasks.Event event) throws Exception {
    List<String> before = sse(true, event);
    List<String> after = sse(false, event);
    assertEquals(before, after);
    var response = TaskEventResponse.from(event);
    assertEquals(1, response.eventVersion());
    assertSame(event.artifactIds(), response.artifactIds());
    assertEquals(
        List.of(
            "eventVersion",
            "eventId",
            "taskId",
            "seq",
            "type",
            "occurredAt",
            "status",
            "code",
            "artifactIds"),
        Arrays.stream(TaskEventResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList());
    assertArrayEquals(
        new Class<?>[] {
          int.class,
          String.class,
          String.class,
          long.class,
          String.class,
          Instant.class,
          String.class,
          String.class,
          List.class
        },
        Arrays.stream(TaskEventResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getType)
            .toArray(Class<?>[]::new));
    assertEquals(event.eventId(), response.eventId());
    assertEquals(event.taskId(), response.taskId());
    assertEquals(event.seq(), response.seq());
    assertEquals(event.type(), response.type());
    assertEquals(event.occurredAt(), response.occurredAt());
    assertEquals(event.status(), response.status());
    assertEquals(event.code(), response.code());
  }

  @Test
  void nullEventRoutingRetainsOriginalFailureAndNullVoFields() throws Exception {
    var event = new Tasks.Event(null, 0, null, null, null, null, null, List.of());
    var envelope =
        loader
            .loadClass(baseline.getName() + "$Envelope")
            .getConstructors()[0]
            .newInstance(1, null, null, 0L, null, null, null, null, event.artifactIds());
    assertArrayEquals(
        JSON.writeValueAsBytes(envelope), JSON.writeValueAsBytes(TaskEventResponse.from(event)));
    String message = null;
    for (boolean old : new boolean[] {true, false}) {
      emitter(old);
      var failure = assertThrows(NullPointerException.class, () -> eventCallback.accept(event));
      if (old) message = failure.getMessage();
      else assertEquals(message, failure.getMessage());
    }
  }

  @Test
  void filenameAndEtagValuesAreNotNormalized() throws Exception {
    for (String artifactId : Arrays.asList(null, "name with spaces", "name\"quoted")) {
      for (String etag : Arrays.asList(null, "", "\"quoted\"", "W/\"weak\"")) {
        content =
            new ArtifactService.Content(
                new Tasks.Artifact(artifactId, "text/plain", 999, etag, null), new byte[0]);
        assertEquals(
            download(true, "trusted", ID).getHeaders(),
            download(false, "trusted", ID).getHeaders());
      }
    }
  }

  private SseEmitter emitter(boolean old) throws Exception {
    return (SseEmitter)
        invoke(
            controller(old),
            "events",
            new Class<?>[] {String.class, String.class, String.class},
            "trusted",
            ID,
            ID + ":2");
  }

  private List<String> sse(boolean old, Tasks.Event event) throws Exception {
    calls.clear();
    var emitter = emitter(old);
    assertEquals(30000L, emitter.getTimeout());
    assertEquals(
        List.of(
            "authenticate:trusted",
            List.of("get", IDENTITY, ID),
            List.of("subscribe", "trusted", ID, 2L)),
        calls);
    var capture = new HandlerCapture();
    capture.initialize(emitter);
    eventCallback.accept(event);
    var expected =
        SseEmitter.event()
            .id(event.eventId())
            .name(event.type())
            .data(TaskEventResponse.from(event))
            .build();
    assertEquals(encode(expected), capture.sent);
    completeCallback.run();
    assertTrue(capture.completed);
    capture.completion.run();
    assertEquals("close", calls.getLast());
    return capture.sent;
  }

  @Test
  void ioFailureAndAllCloseCallbacksKeepOriginalBehavior() throws Exception {
    for (boolean old : new boolean[] {true, false}) {
      for (String callback : List.of("completion", "timeout", "error")) {
        calls.clear();
        var emitter = emitter(old);
        var capture = new HandlerCapture();
        capture.initialize(emitter);
        var io = new IOException("synthetic disconnect");
        capture.io = io;
        var failure =
            assertThrows(
                IllegalStateException.class,
                () -> eventCallback.accept(events().findFirst().orElseThrow()));
        assertEquals("Subscriber disconnected", failure.getMessage());
        assertSame(io, failure.getCause());
        if (callback.equals("completion")) capture.completion.run();
        else if (callback.equals("timeout")) capture.timeout.run();
        else capture.error.accept(io);
        assertEquals(1, calls.stream().filter("close"::equals).count());
      }
    }
  }

  private static List<String> encode(Set<ResponseBodyEmitter.DataWithMediaType> parts) {
    return parts.stream()
        .map(
            part ->
                part.getMediaType()
                    + ":"
                    + (part.getData() instanceof String text
                        ? text
                        : JSON.writeValueAsString(part.getData())))
        .toList();
  }

  /** 在真实 emitter 上安装内存 handler，直接触发框架回调，不创建服务器/线程。 */
  private static final class HandlerCapture {
    List<String> sent;
    Runnable completion;
    Runnable timeout;
    Consumer<Throwable> error;
    boolean completed;
    IOException io;

    @SuppressWarnings("unchecked")
    void initialize(SseEmitter emitter) throws Exception {
      Class<?> handler = Class.forName(ResponseBodyEmitter.class.getName() + "$Handler");
      Object proxy =
          Proxy.newProxyInstance(
              handler.getClassLoader(),
              new Class<?>[] {handler},
              (target, method, args) -> {
                switch (method.getName()) {
                  case "send" -> {
                    if (io != null) throw io;
                    sent = encode((Set<ResponseBodyEmitter.DataWithMediaType>) args[0]);
                  }
                  case "complete" -> completed = true;
                  case "onCompletion" -> completion = (Runnable) args[0];
                  case "onTimeout" -> timeout = (Runnable) args[0];
                  case "onError" -> error = (Consumer<Throwable>) args[0];
                  default -> throw new AssertionError(method);
                }
                return null;
              });
      var initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handler);
      initialize.setAccessible(true);
      initialize.invoke(emitter, proxy);
    }
  }
}
