package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionStorage;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionStorageServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionStorageServiceTest {
  private final QuestionStorageServiceImpl service = new QuestionStorageServiceImpl();

  private static final class Fixture implements QuestionStorage {
    final List<String> calls = new ArrayList<>();
    List<String> caps = List.of("ask_user_question");
    Long bytes = 0L;
    String fail;
    RuntimeException error = new IllegalStateException("original");

    public List<String> capabilities() {
      calls.add("predicate");
      if ("predicate".equals(fail)) throw error;
      return caps;
    }

    public Long bytes() {
      calls.add("query");
      if ("query".equals(fail)) throw error;
      return bytes;
    }
  }

  @Test
  void nonQuestionSkipsQueryRegardlessOfAdditional() {
    var f = new Fixture();
    f.caps = List.of("other");
    f.fail = "query";
    service.requireCapacity(f, Long.MAX_VALUE);
    assertEquals(List.of("predicate"), f.calls);
  }

  @Test
  void predicatePrecedesExactlyOneQueryAndRepeatedCallsReadAgain() {
    var f = new Fixture();
    service.requireCapacity(f, 0);
    service.requireCapacity(f, 0);
    assertEquals(List.of("predicate", "query", "predicate", "query"), f.calls);
  }

  @Test
  void mixedAndDuplicateQuestionCapsStillCount() {
    for (var caps :
        List.of(
            List.of("other", "ask_user_question"),
            List.of("ask_user_question", "ask_user_question"))) {
      var f = new Fixture();
      f.caps = caps;
      f.bytes = 2097153L;
      assertThrows(TaskFailure.class, () -> service.requireCapacity(f, 0));
      assertEquals(List.of("predicate", "query"), f.calls);
    }
  }

  @Test
  void exactLimitAllowed() {
    var f = new Fixture();
    f.bytes = 2000000L;
    service.requireCapacity(f, 2097152 - f.bytes);
  }

  @Test
  void oneByteOverHasOriginalStatusAndCode() {
    var f = new Fixture();
    f.bytes = 2000000L;
    var failure =
        assertThrows(TaskFailure.class, () -> service.requireCapacity(f, 2097152 - f.bytes + 1));
    assertEquals("STORAGE_BUDGET_EXCEEDED", failure.getMessage());
    assertEquals(429, failure.status());
  }

  @Test
  void negativeAdditionalIsNotRejected() {
    var f = new Fixture();
    f.bytes = 2097152L;
    service.requireCapacity(f, -1);
  }

  @Test
  void signedOverflowIsNotCheckedOrSaturated() {
    var f = new Fixture();
    f.bytes = Long.MAX_VALUE;
    service.requireCapacity(f, 1);
    f.bytes = 1L;
    service.requireCapacity(f, Long.MAX_VALUE);
  }

  @Test
  void negativeOverflowStillUsesOriginalLeftAssociatedExpression() {
    var f = new Fixture();
    f.bytes = Long.MIN_VALUE;
    assertThrows(TaskFailure.class, () -> service.requireCapacity(f, -1));
  }

  @Test
  void sqlNullIsUnboxedAtOriginalPoint() {
    var f = new Fixture();
    f.bytes = null;
    assertThrows(NullPointerException.class, () -> service.requireCapacity(f, -1));
    assertEquals(List.of("predicate", "query"), f.calls);
  }

  @Test
  void predicateFailurePropagatesWithoutQuery() {
    var f = new Fixture();
    f.fail = "predicate";
    assertSame(
        f.error, assertThrows(IllegalStateException.class, () -> service.requireCapacity(f, 0)));
    assertEquals(List.of("predicate"), f.calls);
  }

  @Test
  void queryFailureIsNeverTranslatedOrCaught() {
    for (RuntimeException error :
        List.of(
            new IllegalStateException("sql"),
            new NullPointerException("null"),
            new ClassCastException("cast"),
            new TaskFailure(404, "NOT_FOUND"))) {
      var f = new Fixture();
      f.fail = "query";
      f.error = error;
      assertSame(error, assertThrows(error.getClass(), () -> service.requireCapacity(f, 0)));
    }
  }
}
