package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** 通用延迟provider故障被业务catch仍须回滚；close始终执行且不重复挂清理异常。 */
class QueryResultsFailureTest {
  @Test
  void foreignQueryCannotHideTheActualOwnerOrLeaveTheCursorOpen() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var query = query();
    var foreign = query();
    int[] closes = {0};
    RuntimeException[] first = {null};
    var observed =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.transaction.required(
                    query,
                    () -> {
                      first[0] =
                          assertThrows(
                              RuntimeException.class,
                              () -> QueryResults.meter(provider("", null, null, closes), foreign));
                      assertSame(
                          first[0], assertThrows(RuntimeException.class, query::checkHealthy));
                      return null;
                    }));
    assertSame(first[0], observed);
    assertEquals(0, foreign.resources().reads());
    assertEquals(1, closes[0]);
    assertEquals(1, fixture.rollbacks);
  }

  @Test
  void everyProviderEntryPoisonsCaughtFailureAndRefundsOnClose() {
    for (String entry :
        java.util.List.of(
            "getMetaData", "getColumnType", "next", "getObject", "getColumnLabel", "close")) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var query = query();
      var marker = new SQLException("injected " + entry, "08003", 9001);
      int[] closes = {0};
      RuntimeException[] first = {null};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.transaction.required(
                      query,
                      () -> {
                        var provider = provider(entry, marker, null, closes);
                        ResultSet cursor = null;
                        try {
                          cursor = QueryResults.meter(provider, query);
                          cursor.next();
                          if (entry.equals("getColumnType")) cursor.getMetaData().getColumnType(1);
                          cursor.close();
                          fail("Missing injected failure");
                        } catch (SQLException failure) {
                          fail("Owned provider must latch one runtime adaptation", failure);
                        } catch (RuntimeException failure) {
                          first[0] = failure;
                          assertSame(marker, failure.getCause());
                          assertEquals("08003", ((SQLException) failure.getCause()).getSQLState());
                        } finally {
                          if (cursor != null) {
                            try {
                              cursor.close();
                              cursor.close();
                            } catch (SQLException failure) {
                              fail(failure);
                            }
                          }
                        }
                        long operations = query.resources().operations();
                        assertSame(
                            first[0],
                            assertThrows(RuntimeException.class, () -> query.operation(1)));
                        assertSame(first[0], assertThrows(RuntimeException.class, query::result));
                        assertEquals(operations, query.resources().operations());
                        return null;
                      }));
      assertSame(first[0], observed, entry);
      assertEquals(0, fixture.commits, entry);
      assertEquals(1, fixture.rollbacks, entry);
      assertEquals(1, closes[0], entry);
      assertEquals(65536, query.scratchLive(), entry);
    }
  }

  @Test
  void closeFailureIsSuppressedOnceOnOriginalReadAndMetadataErrors() {
    for (String entry : java.util.List.of("getMetaData", "getObject")) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var query = query();
      var marker = new SQLException("read", "08003");
      var cleanup = new SQLException("close", "08006");
      int[] closes = {0};
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.transaction.required(
                      query,
                      () -> {
                        ResultSet cursor = null;
                        RuntimeException primary;
                        try {
                          cursor =
                              QueryResults.meter(provider(entry, marker, cleanup, closes), query);
                          var current = cursor;
                          primary = assertThrows(RuntimeException.class, current::next);
                        } catch (SQLException failure) {
                          throw new AssertionError(failure);
                        } catch (RuntimeException failure) {
                          primary = failure;
                        }
                        if (cursor != null) {
                          var current = cursor;
                          assertSame(primary, assertThrows(RuntimeException.class, current::close));
                          assertDoesNotThrow(current::close);
                        }
                        assertSame(marker, primary.getCause());
                        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
                        return null;
                      }));
      assertSame(marker, observed.getCause());
      assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
      assertEquals(1, closes[0]);
      assertEquals(1, fixture.rollbacks);
      assertEquals(65536, query.scratchLive());
    }
  }

  @Test
  void normalOwnedCursorCommitsAndLegacySqlExceptionStaysOriginal() throws Exception {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var query = query();
    int[] closes = {0};
    fixture.transaction.required(
        query,
        () -> {
          try (var cursor = QueryResults.meter(provider("", null, null, closes), query)) {
            assertTrue(cursor.next());
          } catch (SQLException failure) {
            throw new AssertionError(failure);
          }
          return null;
        });
    assertEquals(1, fixture.commits);
    assertEquals(1, closes[0]);
    var marker = new SQLException("legacy", "08003");
    try (var cursor =
        QueryResults.meter(provider("getObject", marker, null, new int[1]), query())) {
      assertSame(marker, assertThrows(SQLException.class, cursor::next));
    }
  }

  private static SessionQueryContext query() {
    var query =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    query.restrictLineage();
    return query;
  }

  private static ResultSet provider(
      String entry, SQLException marker, SQLException cleanup, int[] closes) {
    var metadata =
        (ResultSetMetaData)
            Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class},
                (p, method, args) -> {
                  if (method.getName().equals(entry)) throw marker;
                  return switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> "x";
                    default -> null;
                  };
                });
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (p, method, args) -> {
              if (method.getName().equals("close")) {
                closes[0]++;
                if (entry.equals("close")) throw marker;
                if (cleanup != null) throw cleanup;
                return null;
              }
              if (method.getName().equals(entry)) throw marker;
              return switch (method.getName()) {
                case "getMetaData" -> metadata;
                case "next" -> true;
                case "getObject" -> "ok";
                default -> null;
              };
            });
  }
}
