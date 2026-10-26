package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.api.UserQuestionController;
import io.github.lovexiaopeng.jimu.api.interaction.dto.QuestionAnswerRequestDecoder;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuestionAnswerRequestDecoderTest {
  private static final String VALID =
      "{\"questionVersion\":1,\"expectedRevision\":2,\"answers\":[{\"id\":\"q\",\"selected\":[]}]}";

  @Test
  void acceptsExactByteLimitAndReadsOnlyOneOverflowByte() throws Exception {
    String body = VALID + " ".repeat(32768 - VALID.length());
    assertEquals(1, decode(body).questionVersion());
    var reads = new AtomicInteger();
    byte[] oversized = (body + " ".repeat(100)).getBytes(StandardCharsets.UTF_8);
    var stream = stream(oversized, reads);
    var failure =
        assertThrows(
            TaskFailure.class, () -> QuestionAnswerRequestDecoder.decode("key", request(stream)));
    assertEquals(413, failure.status());
    assertEquals("REQUEST_TOO_LARGE", failure.code());
    assertEquals(32769, reads.get());
  }

  @Test
  void rejectsInvalidKeysBeforeAcquiringStreamAndAcceptsAsciiBoundaries() throws Exception {
    HttpServletRequest poison =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> {
                  throw new AssertionError("request touched");
                });
    for (String key : new String[] {null, "", " ", "x\n", "中", "x".repeat(129)}) {
      var failure =
          assertThrows(TaskFailure.class, () -> QuestionAnswerRequestDecoder.decode(key, poison));
      assertEquals(400, failure.status());
      assertEquals("INVALID_REQUEST", failure.code());
    }
    assertEquals(
        1,
        QuestionAnswerRequestDecoder.decode(
                "!" + "~".repeat(127),
                request(stream(VALID.getBytes(StandardCharsets.UTF_8), new AtomicInteger())))
            .questionVersion());
  }

  @Test
  void rejectsStrictJsonAmbiguitiesAndWrongTypes() {
    for (String invalid :
        List.of(
            "",
            "null",
            "[]",
            "1",
            VALID + " {}",
            VALID.replace("\"questionVersion\":1", "\"questionVersion\":1,\"questionVersion\":1"),
            VALID.replace("\"id\":\"q\"", "\"id\":\"q\",\"id\":\"q\""),
            VALID.replace("\"id\":\"q\"", "\"id\":{}"),
            VALID.replace("\"selected\":[]", "\"selected\":{}"),
            VALID.replace("\"selected\":[]", "\"selected\":[],\"custom\":{}"),
            VALID.replace("\"selected\":[]", "\"selected\":[],\"custom\":null"),
            VALID.replace("\"answers\":", "\"unknown\":true,\"answers\":"),
            VALID.replace("[]", "[".repeat(16) + "0" + "]".repeat(16)))) {
      invalid(invalid);
    }
    for (String field : List.of("questionVersion", "expectedRevision")) {
      for (String value : List.of("\"1\"", "1.0", "null", "true", "2147483648")) {
        invalid(VALID.replaceFirst("\"" + field + "\":\\d", "\"" + field + "\":" + value));
      }
      invalid(VALID.replaceFirst("\"" + field + "\":\\d,", ""));
    }
  }

  @Test
  void preservesOptionalCustomAndLeavesBusinessValidationToLedger() throws Exception {
    assertNull(decode(VALID).answers().getFirst().custom());
    String custom = "  unchanged 😀  ";
    assertEquals(
        custom,
        decode(VALID.replace("\"selected\":[]", "\"selected\":[],\"custom\":\"" + custom + "\""))
            .answers()
            .getFirst()
            .custom());
    assertEquals(
        -1,
        decode(VALID.replace("\"questionVersion\":1", "\"questionVersion\":-1")).questionVersion());
    assertEquals(
        List.of(), decode(VALID.replace("[{\"id\":\"q\",\"selected\":[]}]", "[]")).answers());
  }

  @Test
  void preservesExistingJacksonNumberToStringBehavior() throws Exception {
    // ALLOW_COERCION_OF_SCALARS未禁止Jackson对String目标的数字转换；提取不改变原策略。
    assertEquals("1", decode(VALID.replace("\"id\":\"q\"", "\"id\":1")).answers().getFirst().id());
    assertEquals(
        List.of("1"),
        decode(VALID.replace("\"selected\":[]", "\"selected\":[1]"))
            .answers()
            .getFirst()
            .selected());
    assertEquals(
        "1",
        decode(VALID.replace("\"selected\":[]", "\"selected\":[],\"custom\":1"))
            .answers()
            .getFirst()
            .custom());
  }

  @Test
  void propagatesAcquisitionAndReadIoFailuresUnchanged() {
    var failure = new IOException("fixture");
    var acquisition =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> {
                  throw failure;
                });
    assertSame(
        failure,
        assertThrows(
            IOException.class, () -> QuestionAnswerRequestDecoder.decode("key", acquisition)));
    var broken =
        new ServletInputStream() {
          public int read() throws IOException {
            throw failure;
          }

          public boolean isReady() {
            return true;
          }

          public boolean isFinished() {
            return false;
          }

          public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException();
          }
        };
    assertSame(
        failure,
        assertThrows(
            IOException.class, () -> QuestionAnswerRequestDecoder.decode("key", request(broken))));
  }

  @Test
  void controllerPassesDecodedCommandAndPreservesFreshAndReplayReceipts() throws Exception {
    var identity = new Identity("tenant", "actor");
    var receipt = Map.<String, Object>of("version", 1, "status", "accepted");
    for (boolean replay : List.of(false, true)) {
      var calls = new java.util.ArrayList<String>();
      var ledger =
          (UserQuestionLedger)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {UserQuestionLedger.class},
                  (p, m, a) -> {
                    calls.add(m.getName());
                    assertSame(identity, a[0]);
                    assertEquals("task", a[1]);
                    assertEquals("request", a[2]);
                    if (m.getName().equals("authorizeAnswer")) return null;
                    assertEquals("answer", m.getName());
                    assertEquals("key", a[3]);
                    assertEquals(decode(VALID), a[4]);
                    return new UserQuestionLedger.Submission(replay, receipt);
                  });
      var controller = new UserQuestionController(t -> identity, ledger);
      var response =
          controller.answer(
              "token",
              "key",
              "task",
              "request",
              request(stream(VALID.getBytes(StandardCharsets.UTF_8), new AtomicInteger())));
      assertEquals(replay ? 200 : 202, response.getStatusCode().value());
      assertSame(receipt, response.getBody());
      assertEquals(List.of("authorizeAnswer", "answer"), calls);
    }
  }

  private static void invalid(String body) {
    var failure = assertThrows(TaskFailure.class, () -> decode(body), body);
    assertEquals(400, failure.status());
    assertEquals("QUESTION_ANSWER_INVALID", failure.code());
  }

  private static UserQuestionLedger.Command decode(String body) throws IOException {
    return QuestionAnswerRequestDecoder.decode(
        "key", request(stream(body.getBytes(StandardCharsets.UTF_8), new AtomicInteger())));
  }

  private static HttpServletRequest request(ServletInputStream stream) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            QuestionAnswerRequestDecoderTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (p, m, a) -> {
              if (!m.getName().equals("getInputStream")) throw new AssertionError(m.getName());
              return stream;
            });
  }

  private static ServletInputStream stream(byte[] body, AtomicInteger reads) {
    var input = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      public int read() {
        int value = input.read();
        if (value != -1) reads.incrementAndGet();
        return value;
      }

      public boolean isReady() {
        return true;
      }

      public boolean isFinished() {
        return input.available() == 0;
      }

      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
