package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionMessagesServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionMessagesServiceTest {
  private final QuestionMessagesServiceImpl service = new QuestionMessagesServiceImpl();

  static class Trace {
    final List<String> calls = new ArrayList<>();
    List<String> rows = List.of("a");
    boolean question = true;
    String digest = "h:first";
    int ids, answers;
    int fail = -1;
    Throwable failure;
    boolean nullHash;

    @SuppressWarnings("unchecked")
    QuestionMessages<String, String, String> port() {
      return (QuestionMessages<String, String, String>)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionMessages.class},
              (p, m, a) -> {
                String name = m.getName();
                calls.add(name + (a == null ? "" : java.util.Arrays.asList(a).toString()));
                if (calls.size() == fail) throw failure;
                return switch (name) {
                  case "questionTask" -> question;
                  case "tenant" -> "t";
                  case "taskId" -> "task";
                  case "pending" -> rows;
                  case "answer" -> ++answers % 2 == 1 ? "first" : "second";
                  case "hash" -> nullHash ? null : "h:" + a[0];
                  case "answerDigest" -> digest;
                  case "id" -> ++ids % 2 == 1 ? "key" : "request";
                  case "provider" -> "provider";
                  case "questions" -> "questions";
                  case "read" -> "read:" + a[0];
                  case "encode" -> a[3] + ":" + a[6];
                  default -> throw new AssertionError(name);
                };
              });
    }
  }

  @Test
  void skipAndEmptyReturnFreshMutableOrderedMaps() {
    var t = new Trace();
    t.question = false;
    var a = service.messages(t.port());
    var b = service.messages(t.port());
    assertInstanceOf(LinkedHashMap.class, a);
    assertNotSame(a, b);
    a.put(null, "mutable");
    assertEquals(List.of("questionTask", "questionTask"), t.calls);
    t = new Trace();
    t.rows = List.of();
    assertEquals(Map.of(), service.messages(t.port()));
    assertEquals(List.of("questionTask", "tenant", "taskId", "pending[t, task]"), t.calls);
  }

  @Test
  void retainsIndependentReadsAndExactArgumentOrder() {
    var t = new Trace();
    var result = service.messages(t.port());
    assertEquals(Map.of("key", "request:read:second"), result);
    assertEquals(
        List.of(
            "questionTask",
            "tenant",
            "taskId",
            "pending[t, task]",
            "answer[a]",
            "hash[first]",
            "answerDigest[a]",
            "id[a]",
            "id[a]",
            "provider[a]",
            "questions[a]",
            "answer[a]",
            "read[second]",
            "encode[1, answer_to_pending_question, ask_user_question, request, provider, questions, read:second]"),
        t.calls);
  }

  @Test
  void digestFailureStopsThisRowAndAllLaterRows() {
    var t = new Trace();
    t.rows = List.of("a", "b");
    t.digest = "bad";
    assertEquals(
        "SNAPSHOT_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.messages(t.port())).getMessage());
    assertEquals(7, t.calls.size());
    assertEquals("answerDigest[a]", t.calls.getLast());
  }

  @Test
  void nullHashStillReadsDigestBeforeOriginalNullReceiverFailure() {
    var t = new Trace();
    t.nullHash = true;
    assertThrows(NullPointerException.class, () -> service.messages(t.port()));
    assertEquals("answerDigest[a]", t.calls.getLast());
  }

  @Test
  void reachedRuntimeAndErrorRemainSameAndStopImmediately() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("runtime"),
            new AssertionError("error"),
            new TaskFailure(409, "ORIGINAL"))) {
      for (int i = 1; i <= 14; i++) {
        var t = new Trace();
        t.fail = i;
        t.failure = failure;
        assertSame(failure, assertThrows(Throwable.class, () -> service.messages(t.port())));
        assertEquals(i, t.calls.size());
      }
    }
  }

  @Test
  void duplicateAndNullKeysRetainLinkedHashMapPutSemantics() {
    var t = new Trace();
    t.rows = List.of("a", "b", "c");
    var p = t.port();
    final int[] reads = {0};
    @SuppressWarnings("unchecked")
    var wrapped =
        (QuestionMessages<String, String, String>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {QuestionMessages.class},
                (o, m, a) -> {
                  if (m.getName().equals("id")) {
                    int n = reads[0]++;
                    return n / 2 == 1 ? null : "same";
                  }
                  try {
                    return m.invoke(p, a);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    var result = service.messages(wrapped);
    assertEquals(java.util.Arrays.asList("same", null), new ArrayList<>(result.keySet()));
    assertEquals(2, result.size());
    result.put("later", "mutable");
  }
}
