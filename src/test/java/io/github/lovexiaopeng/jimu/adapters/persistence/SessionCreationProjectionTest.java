package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionCreationProjectionTest {
  private static final class Rows {
    final Map<String, Object> sizes =
        new HashMap<>(Map.of("goal", 5L, "request_snapshot", 2L, "capabilities", 3L));
    final Map<String, Object> row =
        new HashMap<>(
            Map.of(
                "tenant_id",
                "tenant",
                "id",
                "task",
                "goal",
                "hello",
                "request_snapshot",
                "{}",
                "capabilities",
                "abc",
                "owner_actor_id",
                "actor",
                "tools_digest",
                "digest",
                "installation_epoch",
                1L,
                "state",
                "QUEUED",
                "created_at",
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0)));
    final List<Map<String, Object>> sizeList = new ArrayList<>(List.of(sizes));
    final List<Map<String, Object>> values = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    Runnable beforeRow = () -> {};

    Rows() {
      row.put("request_digest", "request");
      values.add(row);
    }

    final TaskAdmissionMapper sql =
        (TaskAdmissionMapper)
            Proxy.newProxyInstance(
                TaskAdmissionMapper.class.getClassLoader(),
                new Class<?>[] {TaskAdmissionMapper.class},
                (proxy, method, args) -> {
                  calls.add(method.getName());
                  if (method.getName().equals("lockSessionCreationSizes")) return sizeList;
                  if (method.getName().equals("lockSessionCreation")) {
                    beforeRow.run();
                    return values;
                  }
                  throw new AssertionError(method.getName());
                });
  }

  private static SessionQueryContext query() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    return q;
  }

  @Test
  void consumerOwnsCompleteRowUntilInitializationReturnsThenAliasesClear() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var rows = new Rows();
    f.transaction.required(
        q,
        () -> {
          SessionQueryTransaction.current(q).locked("tenant", "task");
          long base = q.scratchLive();
          SessionAcceptedTaskRows.creation(
              rows.sql,
              q,
              "tenant",
              "task",
              row -> {
                assertEquals(11, row.size());
                assertEquals("{}", row.get("request_snapshot"));
                assertTrue(q.scratchLive() >= base + 8192 + 8 * 10);
                assertTrue(rows.sizes.isEmpty());
              });
          assertEquals(base, q.scratchLive());
          assertTrue(rows.row.isEmpty());
          return null;
        });
    assertEquals(List.of("lockSessionCreationSizes", "lockSessionCreation"), rows.calls);
    assertTrue(rows.values.isEmpty());
    assertTrue(rows.sizeList.isEmpty());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void firstConsumerErrorRollsBackAndReleasesRowOwnership() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var rows = new Rows();
    var error = new AssertionError("consumer");
    assertSame(
        error,
        assertThrows(
            AssertionError.class,
            () ->
                f.transaction.required(
                    q,
                    () -> {
                      SessionQueryTransaction.current(q).locked("tenant", "task");
                      SessionAcceptedTaskRows.creation(
                          rows.sql,
                          q,
                          "tenant",
                          "task",
                          row -> {
                            throw error;
                          });
                      return null;
                    })));
    assertTrue(rows.values.isEmpty());
    assertTrue(rows.row.isEmpty());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void overlargeReservationRefusesBeforeReadingRaw() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var rows = new Rows();
    rows.sizes.put("request_snapshot", 1048576L);
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  SessionQueryTransaction.current(q).locked("tenant", "task");
                  SessionAcceptedTaskRows.creation(
                      rows.sql,
                      q,
                      "tenant",
                      "task",
                      row -> {
                        throw new AssertionError("unreached");
                      });
                  return null;
                }));
    assertEquals(List.of("lockSessionCreationSizes"), rows.calls);
    assertTrue(rows.sizes.isEmpty());
    assertEquals(65536, q.scratchLive());
  }

  @Test
  void writeBetweenSizeAndRawCannotPublishRow() {
    var f = new SessionQueryTransactionTest.Fixture();
    var q = query();
    var rows = new Rows();
    rows.beforeRow = () -> SessionQueryTransaction.current(q).mutation();
    assertThrows(
        RuntimeException.class,
        () ->
            f.transaction.required(
                q,
                () -> {
                  SessionQueryTransaction.current(q).locked("tenant", "task");
                  SessionAcceptedTaskRows.creation(
                      rows.sql,
                      q,
                      "tenant",
                      "task",
                      row -> {
                        throw new AssertionError("unreached");
                      });
                  return null;
                }));
    assertTrue(rows.row.isEmpty());
    assertEquals(65536, q.scratchLive());
  }
}
