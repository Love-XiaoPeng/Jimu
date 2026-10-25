package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryViews;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionHistoryViewServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 真实端口的惰性字段读取、可读性短路、原 wire 类型和负 upper 算术。 */
class SessionHistoryViewServiceTest {
  private final SessionHistoryViewServiceImpl service = new SessionHistoryViewServiceImpl();

  private static final class View implements SessionHistoryViews.Projection {
    final List<String> calls = new ArrayList<>();
    String state = "PRESENT";
    long retained = 1;
    boolean scoped;
    RuntimeException recentFailure;
    LinkedHashMap<String, Object> value;
    final List<?> rows =
        List.of(new LinkedHashMap<>(java.util.Map.of("seq", 0L, "type", "user.message")));

    public SessionHistoryViews.History history() {
      calls.add("history(false)");
      return new SessionHistoryViews.History() {
        public long nextSeq() {
          calls.add("next_seq");
          return 1;
        }

        public String contentState() {
          calls.add("content_state");
          return state;
        }

        public long retained() {
          calls.add("retained");
          return retained;
        }

        public String retainUntil() {
          calls.add("retain_until");
          return "2026-10-25T01:02:03.123456Z";
        }
      };
    }

    public int version() {
      calls.add("version");
      return scoped ? 4 : 1;
    }

    public String sessionId() {
      calls.add("id");
      return "id";
    }

    public List<?> recent(long upper) {
      calls.add("recent:" + upper);
      if (recentFailure != null) throw recentFailure;
      return rows;
    }

    public long asOfSseSeq() {
      calls.add("sse");
      return 7L;
    }

    public boolean scopeTask() {
      calls.add("scopeTask");
      return scoped;
    }

    public SessionHistoryViews.Scope requireScope() {
      calls.add("require");
      return new SessionHistoryViews.Scope() {
        public String digest() {
          calls.add("digest");
          return "digest";
        }

        public String state() {
          calls.add("scope_state");
          return "ACTIVE";
        }
      };
    }

    public String encode(LinkedHashMap<String, Object> value) {
      calls.add("json");
      this.value = value;
      return "original raw";
    }

    public void validateOwner(String raw) {
      calls.add("owner:" + raw);
    }
  }

  @Test
  void ownerKeepsTransactionLockAndProjectionOrderAndWireTypes() {
    var view = new View();
    assertEquals(
        "original raw",
        service.owner(
            new SessionHistoryViews.Owner() {
              public <T> T required(Supplier<T> work) {
                view.calls.add("required");
                var result = work.get();
                view.calls.add("exit");
                return result;
              }

              public SessionHistoryViews.Projection lock() {
                view.calls.add("ownerLock");
                return view;
              }
            }));
    assertEquals(
        List.of(
            "required",
            "ownerLock",
            "history(false)",
            "next_seq",
            "content_state",
            "retained",
            "version",
            "id",
            "content_state",
            "recent:0",
            "sse",
            "retain_until",
            "scopeTask",
            "json",
            "owner:original raw",
            "exit"),
        view.calls);
    assertEquals(
        List.of(
            "version",
            "session_id",
            "content_state",
            "upper_seq",
            "recent",
            "user_message_seq",
            "as_of_sse_seq",
            "retain_until",
            "readable"),
        new ArrayList<>(view.value.keySet()));
    assertSame(view.rows, view.value.get("recent"));
    assertInstanceOf(Integer.class, view.value.get("version"));
    assertInstanceOf(Integer.class, view.value.get("user_message_seq"));
    assertInstanceOf(Long.class, view.value.get("upper_seq"));
    assertInstanceOf(Long.class, view.value.get("as_of_sse_seq"));
    assertInstanceOf(Boolean.class, view.value.get("readable"));
  }

  @Test
  void nonPresentSkipsRetainedAndRecentButStillRequiresScopeAtOriginalPosition() {
    var view = new View();
    view.scoped = true;
    view.state = "PURGED";
    service.metadata(view);
    assertFalse(view.calls.contains("retained"));
    assertFalse(view.calls.contains("recent:0"));
    assertEquals(
        List.of(
            "history(false)",
            "next_seq",
            "content_state",
            "version",
            "id",
            "content_state",
            "sse",
            "retain_until",
            "scopeTask",
            "require",
            "digest",
            "scope_state",
            "json",
            "owner:original raw"),
        view.calls);
    assertEquals(List.of(), view.value.get("recent"));
    assertEquals(
        List.of("scope_sha256", "scope_state"),
        new ArrayList<>(view.value.keySet()).subList(9, 11));
  }

  @Test
  void retainedFalseSkipsOnlyRecent() {
    var view = new View();
    view.retained = 0;
    service.metadata(view);
    assertTrue(view.calls.contains("retained"));
    assertFalse(view.calls.contains("recent:0"));
    assertEquals(false, view.value.get("readable"));
  }

  @Test
  void recentFailurePrecedesScopeAndSerialization() {
    var view = new View();
    view.scoped = true;
    view.recentFailure = new IllegalStateException("original recent");
    assertSame(
        view.recentFailure,
        assertThrows(IllegalStateException.class, () -> service.metadata(view)));
    assertFalse(view.calls.contains("sse"));
    assertFalse(view.calls.contains("require"));
    assertNull(view.value);
  }

  @Test
  void emptyAndNegativeUpperStillExecuteOriginalQuery() {
    var calls = new ArrayList<Long>();
    SessionHistoryViews.RecentRows empty = () -> 0;
    assertSame(
        empty,
        service.recent(
            upper -> {
              calls.add(upper);
              return empty;
            },
            -1));
    assertThrows(
        TaskFailure.class,
        () ->
            service.recent(
                upper -> {
                  calls.add(upper);
                  return empty;
                },
                -2));
    assertEquals(List.of(-1L, -2L), calls);
  }

  @Test
  void recentWindowSizeAndOverflowKeepOriginalCorruptBoundary() {
    for (long upper : new long[] {0, 1, 7, 8, 127}) {
      int count = (int) Math.min(8, upper + 1);
      assertEquals(count, service.recent(value -> () -> count, upper).size());
      var failure =
          assertThrows(TaskFailure.class, () -> service.recent(value -> () -> count - 1, upper));
      assertEquals("SESSION_HISTORY_CORRUPT", failure.getMessage());
    }
    assertThrows(TaskFailure.class, () -> service.recent(value -> () -> 0, Long.MAX_VALUE));
  }
}
