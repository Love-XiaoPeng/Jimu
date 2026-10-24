package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts;
import io.github.lovexiaopeng.jimu.kernel.session.service.impl.SessionContextServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 校验拒绝与计账的实际顺序，防止拆层后先计账再验证。 */
class SessionContextServiceTest {
  private final List<String> calls = new ArrayList<>();
  private boolean missing;
  private boolean expired;
  private final SessionContexts.Task task =
      new SessionContexts.Task() {
        public String id() {
          return "session";
        }

        public int version() {
          return 4;
        }

        public long upper() {
          calls.add("history");
          return 9;
        }

        public List<SessionContexts.Recent> recent(long upper) {
          calls.add("recent");
          return java.util.stream.LongStream.range(missing ? 3 : 2, 10)
              .mapToObj(n -> new SessionContexts.Recent(n, "tool.completed"))
              .toList();
        }

        public SessionContexts.Scope scope() {
          calls.add("scope");
          if (expired) throw new TaskFailure(410, "SESSION_SCOPE_EXPIRED");
          return new SessionContexts.Scope("digest", "retain", List.of());
        }

        public String encode(SessionContexts.Context context) {
          calls.add("encode");
          assertEquals(9, context.upper());
          assertEquals(2, context.recent().getFirst().seq());
          return "frozen-context";
        }

        public void account() {
          calls.add("account");
        }
      };
  private final SessionContextServiceImpl service =
      new SessionContextServiceImpl(
          new SessionContexts() {
            public <T> T required(Supplier<T> work) {
              calls.add("begin");
              try {
                T result = work.get();
                calls.add("commit");
                return result;
              } catch (RuntimeException failure) {
                calls.add("rollback");
                throw failure;
              }
            }

            public Task lock(Lease lease) {
              calls.add("fence");
              return task;
            }
          });

  @Test
  void validatedSnapshotIsAccountedBeforeCommit() {
    assertEquals("frozen-context", service.context(null));
    assertEquals(
        List.of("begin", "fence", "history", "recent", "scope", "encode", "account", "commit"),
        calls);
  }

  @Test
  void IncompleteWindowRejectsBeforeScopeAndAccounting() {
    missing = true;
    assertEquals(
        "SESSION_HISTORY_CORRUPT",
        assertThrows(TaskFailure.class, () -> service.context(null)).code());
    assertEquals(List.of("begin", "fence", "history", "recent", "rollback"), calls);
  }

  @Test
  void currentScopeRefusalDoesNotAccountOrEncode() {
    expired = true;
    assertEquals(
        "SESSION_SCOPE_EXPIRED",
        assertThrows(TaskFailure.class, () -> service.context(null)).code());
    assertEquals(List.of("begin", "fence", "history", "recent", "scope", "rollback"), calls);
  }
}
