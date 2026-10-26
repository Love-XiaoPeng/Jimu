package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.api.UserQuestionController;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionAuthorizationControllerTest {
  @Test
  void authenticationAndAuthorizationPrecedeKeyAndPoisonBody() throws Exception {
    var calls = new ArrayList<String>();
    var identity = new Identity("tenant", "actor");
    var missing = new TaskFailure(404, "QUESTION_NOT_FOUND");
    var ledger = ledger(calls, identity, missing);
    Authenticator auth =
        token -> {
          calls.add("auth");
          return identity;
        };
    var c = new UserQuestionController(auth, ledger);
    HttpServletRequest poison =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> {
                  throw new AssertionError("request read " + m.getName());
                });
    assertSame(
        missing,
        assertThrows(TaskFailure.class, () -> c.answer("token", null, "task", "request", poison)));
    assertEquals(List.of("auth", "authorizeAnswer"), calls);
    calls.clear();
    var forbidden = new TaskFailure(401, "AUTH");
    final var unauth =
        new UserQuestionController(
            t -> {
              calls.add("auth");
              throw forbidden;
            },
            ledger);
    assertSame(
        forbidden,
        assertThrows(
            TaskFailure.class, () -> unauth.answer("bad", "key", "task", "request", poison)));
    assertEquals(List.of("auth"), calls);
  }

  @Test
  void successfulQualificationStillValidatesKeyBodyAndOnlyThenCallsAnswer() throws Exception {
    var calls = new ArrayList<String>();
    var identity = new Identity("tenant", "actor");
    var c =
        new UserQuestionController(
            t -> {
              calls.add("auth");
              return identity;
            },
            ledger(calls, identity, null));
    var req = new Body(calls);
    req.setContent("bad json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        "INVALID_REQUEST",
        assertThrows(
                TaskFailure.class, () -> c.answer("token", null, "task", "request", req.request))
            .code());
    assertEquals(List.of("auth", "authorizeAnswer"), calls);
    calls.clear();
    assertEquals(
        "QUESTION_ANSWER_INVALID",
        assertThrows(
                TaskFailure.class, () -> c.answer("token", "key", "task", "request", req.request))
            .code());
    assertEquals(List.of("auth", "authorizeAnswer", "body"), calls);
    calls.clear();
    req.setContent(new byte[32769]);
    assertEquals(
        "REQUEST_TOO_LARGE",
        assertThrows(
                TaskFailure.class, () -> c.answer("token", "key", "task", "request", req.request))
            .code());
    assertEquals(List.of("auth", "authorizeAnswer", "body"), calls);
    calls.clear();
    req.setContent(
        "{\"questionVersion\":1,\"expectedRevision\":2,\"answers\":[]}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        202, c.answer("token", "key", "task", "request", req.request).getStatusCode().value());
    assertEquals(List.of("auth", "authorizeAnswer", "body", "answer"), calls);
  }

  private static final class Body {
    private byte[] content = new byte[0];
    private final HttpServletRequest request;

    void setContent(byte[] bytes) {
      content = bytes;
    }

    Body(List<String> calls) {
      request =
          (HttpServletRequest)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {HttpServletRequest.class},
                  (p, m, a) -> {
                    if (!m.getName().equals("getInputStream"))
                      throw new AssertionError(m.getName());
                    calls.add("body");
                    var bytes = new java.io.ByteArrayInputStream(content);
                    return new jakarta.servlet.ServletInputStream() {
                      public int read() {
                        return bytes.read();
                      }

                      public boolean isReady() {
                        return true;
                      }

                      public boolean isFinished() {
                        return bytes.available() == 0;
                      }

                      public void setReadListener(jakarta.servlet.ReadListener listener) {
                        throw new UnsupportedOperationException();
                      }
                    };
                  });
    }
  }

  private UserQuestionLedger ledger(List<String> calls, Identity identity, TaskFailure fail) {
    return (UserQuestionLedger)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {UserQuestionLedger.class},
            (p, m, a) -> {
              calls.add(m.getName());
              assertSame(identity, a[0]);
              assertEquals("task", a[1]);
              assertEquals("request", a[2]);
              if (m.getName().equals("authorizeAnswer")) {
                if (fail != null) throw fail;
                return null;
              }
              if (m.getName().equals("answer"))
                return new UserQuestionLedger.Submission(false, Map.of("version", 1));
              throw new AssertionError(m.getName());
            });
  }
}
