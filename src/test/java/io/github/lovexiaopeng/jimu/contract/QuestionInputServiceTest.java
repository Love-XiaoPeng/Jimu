package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionInput;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionInputServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionInputServiceTest {
  private final QuestionInputServiceImpl service = new QuestionInputServiceImpl();
  private static final List<String> ORDER =
      List.of(
          "record",
          "question",
          "binding",
          "taskBinding",
          "raw1",
          "hash:first",
          "digest",
          "tools/pin",
          "version",
          "raw2",
          "codec:v:second");

  static class Port implements QuestionInput<Object, Object> {
    final List<String> calls = new ArrayList<>();
    final Object returned = new Object();
    final Object pin = new Object();
    long record = 1, question = 1;
    String binding = "binding", taskBinding = "binding", digest = "hash";
    String failAt;
    Throwable failure;
    int reads;

    void called(String name) {
      calls.add(name);
      if (name.equals(failAt)) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
      }
    }

    public long recordVersion() {
      called("record");
      return record;
    }

    public long questionVersion() {
      called("question");
      return question;
    }

    public String bindingDigest() {
      called("binding");
      return binding;
    }

    public String taskBindingDigest() {
      called("taskBinding");
      return taskBinding;
    }

    public String requestSnapshot() {
      called("raw" + ++reads);
      return reads == 1 ? "first" : "second";
    }

    public String hash(String raw) {
      called("hash:" + raw);
      return "hash";
    }

    public String requestDigest() {
      called("digest");
      return digest;
    }

    public Object pin() {
      called("tools/pin");
      return pin;
    }

    public String version(Object value) {
      called("version");
      assertSame(pin, value);
      return "v";
    }

    public Object input(String version, String raw) {
      called("codec:" + version + ":" + raw);
      return returned;
    }
  }

  @Test
  void completeOrderSecondSnapshotAndOriginalReturnInstance() {
    var p = new Port();
    assertSame(p.returned, service.input(p));
    assertEquals(ORDER, p.calls);
    assertEquals(2, p.reads);
  }

  @Test
  void allFourOrTermsShortCircuitBeforeLaterMembers() {
    for (int term = 0; term < 4; term++) {
      var p = new Port();
      if (term == 0) p.record = Long.MIN_VALUE;
      if (term == 1) p.question = 2;
      if (term == 2) p.taskBinding = null;
      if (term == 3) p.digest = null;
      var failure = assertThrows(TaskFailure.class, () -> service.input(p));
      assertEquals(409, failure.status());
      assertEquals("SNAPSHOT_CORRUPT", failure.code());
      assertEquals(ORDER.subList(0, new int[] {1, 2, 4, 7}[term]), p.calls);
    }
  }

  @Test
  void nullBindingReceiverStillEvaluatesTaskArgument() {
    var p = new Port();
    p.binding = null;
    assertThrows(NullPointerException.class, () -> service.input(p));
    assertEquals(ORDER.subList(0, 4), p.calls);
  }

  @Test
  void argumentFailureWinsOverNullBindingReceiver() {
    var p = new Port();
    p.binding = null;
    p.failAt = "taskBinding";
    var error = new ClassCastException("task field");
    p.failure = error;
    assertSame(error, assertThrows(ClassCastException.class, () -> service.input(p)));
    assertEquals(ORDER.subList(0, 4), p.calls);
  }

  @Test
  void everyRuntimeFailureAndErrorPropagatesAtOriginalPoint() {
    for (String at : ORDER) {
      for (Throwable error : List.of(new IllegalStateException(at), new AssertionError(at))) {
        var p = new Port();
        p.failAt = at;
        p.failure = error;
        assertSame(error, assertThrows(Throwable.class, () -> service.input(p)));
        assertEquals(ORDER.subList(0, ORDER.indexOf(at) + 1), p.calls);
      }
    }
  }

  @Test
  void missingPinFailsBeforeSecondRaw() {
    var p =
        new Port() {
          public String version(Object value) {
            called("version");
            throw new NullPointerException("pin");
          }
        };
    assertThrows(NullPointerException.class, () -> service.input(p));
    assertEquals(ORDER.subList(0, 9), p.calls);
    assertEquals(1, p.reads);
  }

  @Test
  void codecTaskFailureIsNotTranslated() {
    var p = new Port();
    p.failAt = "codec:v:second";
    var error = new TaskFailure(400, "TOOL_INPUT_INVALID");
    p.failure = error;
    assertSame(error, assertThrows(TaskFailure.class, () -> service.input(p)));
    assertEquals(ORDER, p.calls);
  }
}
