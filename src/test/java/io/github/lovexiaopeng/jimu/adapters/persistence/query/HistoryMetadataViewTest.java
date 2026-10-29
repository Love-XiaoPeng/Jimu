package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Legal Work/lock/size-provider boundary; no synthetic length capability or authorization. */
class HistoryMetadataViewTest {
  static final class Provider {
    final SessionHistoryLengthTest.Fixture fixture;
    final SessionQueryContext query;
    final SessionHistoryRows.Slot slot;
    final PreparedStatement statement;
    final ResultSet delegate;
    final SQLException read = new SQLException("metadata getter", "HY071");
    final SQLException cleanup = new SQLException("driver close", "08006");
    ResultSet rows;
    Object state;
    boolean failGetter, failClose, failMetadata;
    int getters, closes;
    Runnable onClose = () -> {};

    Provider(SessionHistoryLengthTest.Fixture fixture, SessionQueryContext query) throws Exception {
      this.fixture = fixture;
      this.query = query;
      slot = SessionQueryTransaction.current(query).beginHistory("t", "x");
      var original = fixture.connection.prepareStatement(" AS binding_snapshot_utf8_bytes");
      var raw = original.getResultSet();
      var metadata = raw.getMetaData();
      var driverMetadata =
          SessionHistoryLengthTest.proxy(
              ResultSetMetaData.class,
              (p, method, args) -> {
                getters++;
                if (failGetter) throw read;
                return SessionHistoryLengthTest.call(method, metadata, args);
              });
      delegate =
          SessionHistoryLengthTest.proxy(
              ResultSet.class,
              (p, method, args) -> {
                if (method.getName().equals("getMetaData")) {
                  if (failMetadata) throw read;
                  return driverMetadata;
                }
                if (method.getName().equals("close")) {
                  closes++;
                  onClose.run();
                  if (failClose) throw cleanup;
                }
                return SessionHistoryLengthTest.call(method, raw, args);
              });
      statement =
          SessionHistoryLengthTest.proxy(
              PreparedStatement.class,
              (p, method, args) ->
                  switch (method.getName()) {
                    case "getResultSet" -> delegate;
                    case "getConnection" -> fixture.connection;
                    default -> SessionHistoryLengthTest.call(method, original, args);
                  });
    }

    void open() throws Exception {
      var params = Map.of("query", query, "tenant", "t", "taskId", "x");
      slot.prepared(SessionHistoryRows.SIZE, params, fixture.connection, statement);
      rows =
          QueryResults.meterHistory(
              delegate, query, SessionHistoryRows.SIZE, slot, params, statement);
      var handler = Proxy.getInvocationHandler(rows);
      for (var field : handler.getClass().getDeclaredFields()) {
        if (field.getType().getSimpleName().equals("HistoryState")) {
          field.setAccessible(true);
          state = field.get(handler);
        }
      }
      assertTrue(state != null);
    }

    void detached(boolean complete) {
      for (String field :
          complete
              ? new String[] {"delegate", "metadata", "metadataView", "result", "length"}
              : new String[] {"delegate", "metadata", "metadataView"}) {
        assertNull(field(state, field), field);
      }
    }
  }

  static Object field(Object target, String name) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void oneViewStillChargesEveryLookupAndCurrentMetadataGetter() throws Exception {
    var fixture = new SessionHistoryLengthTest.Fixture();
    var query = SessionEventHistoryLengthTest.query();
    ResultSetMetaData[] external = {null};
    Provider[] saved = {null};
    fixture.work(
        query,
        () -> {
          try {
            var provider = new Provider(fixture, query);
            saved[0] = provider;
            provider.open();
            long live = query.scratchLive(), before = query.resources().operations();
            external[0] = provider.rows.getMetaData();
            assertEquals(11, query.resources().operations() - before);
            before = query.resources().operations();
            for (int i = 0; i < 100; i++) assertSame(external[0], provider.rows.getMetaData());
            assertEquals(300, query.resources().operations() - before);
            before = query.resources().operations();
            int calls = provider.getters;
            assertEquals(1, external[0].getColumnCount());
            assertEquals(4, query.resources().operations() - before);
            assertEquals(calls + 1, provider.getters);
            assertEquals(live, query.scratchLive());
            provider.onClose =
                () -> {
                  provider.detached(false);
                  assertEquals(live, query.scratchLive());
                };
            provider.rows.close();
            provider.rows.close();
            provider.detached(true);
            assertEquals(1, provider.closes);
            return null;
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        });
    assertEquals(65536, query.scratchLive());
    int calls = saved[0].getters;
    var closed = assertThrows(RuntimeException.class, external[0]::getColumnCount);
    assertEquals("Completed session transaction", closed.getMessage());
    assertEquals(calls, saved[0].getters);
    saved[0].detached(true);
  }

  @Test
  void firstAllocationAndCachedAccessRefuseBeforePublicationAtTheirExactBoundaries()
      throws Exception {
    for (boolean cached : new boolean[] {false, true}) {
      long required = cached ? 3 : 11;
      for (long available : new long[] {required - 1, required, required + 1}) {
        var fixture = new SessionHistoryLengthTest.Fixture();
        var query = SessionEventHistoryLengthTest.query();
        RuntimeException[] first = {null};
        Runnable action =
            () ->
                fixture.work(
                    query,
                    () -> {
                      try {
                        var provider = new Provider(fixture, query);
                        provider.open();
                        var previous = cached ? provider.rows.getMetaData() : null;
                        query.operation(65536 - available - query.resources().operations());
                        if (available < required) {
                          first[0] =
                              assertThrows(RuntimeException.class, provider.rows::getMetaData);
                          assertSame(previous, field(provider.state, "metadataView"));
                          long after = query.resources().operations();
                          assertSame(
                              first[0],
                              assertThrows(RuntimeException.class, provider.rows::getMetaData));
                          assertEquals(after, query.resources().operations());
                          provider.rows.close();
                          provider.detached(true);
                        } else {
                          var view = provider.rows.getMetaData();
                          if (cached) assertSame(previous, view);
                          assertEquals(
                              65536 - available + required, query.resources().operations());
                          provider.rows.close();
                          provider.detached(true);
                        }
                        return null;
                      } catch (SQLException failure) {
                        throw new AssertionError(failure);
                      } catch (RuntimeException failure) {
                        throw failure;
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    });
        if (available < required) {
          var thrown = assertThrows(RuntimeException.class, action::run);
          assertSame(first[0], thrown);
          assertSame(thrown, query.recordedFailure());
          assertEquals(1, fixture.database.rollbacks);
          assertEquals(0, fixture.database.commits);
        } else action.run();
        assertEquals(65536, query.scratchLive());
      }
    }
  }

  @Test
  void closeWithoutMetadataViewUsesPrepaidCleanupAtExhaustedBudget() throws Exception {
    var fixture = new SessionHistoryLengthTest.Fixture();
    var query = SessionEventHistoryLengthTest.query();
    Provider[] saved = {null};
    RuntimeException[] first = {null};
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.work(
                    query,
                    () -> {
                      try {
                        var provider = new Provider(fixture, query);
                        saved[0] = provider;
                        provider.open();
                        assertNull(field(provider.state, "metadataView"));
                        long live = query.scratchLive();
                        provider.onClose =
                            () -> {
                              provider.detached(false);
                              assertEquals(live, query.scratchLive());
                            };
                        query.operation(65536 - query.resources().operations());
                        first[0] = assertThrows(RuntimeException.class, () -> query.operation(1));
                        long before = query.resources().operations();
                        provider.rows.close();
                        provider.rows.close();
                        assertEquals(before, query.resources().operations());
                        provider.detached(true);
                        return null;
                      } catch (SQLException failure) {
                        throw new AssertionError(failure);
                      } catch (RuntimeException failure) {
                        throw failure;
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }));
    assertSame(first[0], thrown);
    assertEquals(1, saved[0].closes);
    assertEquals(0, fixture.database.commits);
    assertEquals(1, fixture.database.rollbacks);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void getterAndDriverCloseKeepFirstSqlCauseAndSuppressCleanupExactlyOnce() throws Exception {
    for (boolean getter : new boolean[] {false, true}) {
      var fixture = new SessionHistoryLengthTest.Fixture();
      var query = SessionEventHistoryLengthTest.query();
      Provider[] saved = {null};
      RuntimeException[] first = {null};
      var thrown =
          assertThrows(
              RuntimeException.class,
              () ->
                  fixture.work(
                      query,
                      () -> {
                        try {
                          var provider = new Provider(fixture, query);
                          saved[0] = provider;
                          provider.open();
                          var view = provider.rows.getMetaData();
                          provider.failGetter = getter;
                          provider.failClose = true;
                          provider.onClose = () -> provider.detached(false);
                          if (getter)
                            first[0] = assertThrows(RuntimeException.class, view::getColumnCount);
                          var close = assertThrows(RuntimeException.class, provider.rows::close);
                          if (getter) assertSame(first[0], close);
                          else first[0] = close;
                          long after = query.resources().operations();
                          assertSame(
                              first[0], assertThrows(RuntimeException.class, view::getColumnCount));
                          assertEquals(after, query.resources().operations());
                          provider.rows.close();
                          provider.detached(true);
                          return null;
                        } catch (Exception failure) {
                          throw new AssertionError(failure);
                        }
                      }));
      assertSame(first[0], thrown);
      assertSame(getter ? saved[0].read : saved[0].cleanup, thrown.getCause());
      assertEquals(getter ? "HY071" : "08006", ((SQLException) thrown.getCause()).getSQLState());
      assertArrayEquals(
          getter ? new Throwable[] {saved[0].cleanup} : new Throwable[0], thrown.getSuppressed());
      assertEquals(1, saved[0].closes);
      assertEquals(1, fixture.database.rollbacks);
      assertEquals(65536, query.scratchLive());
    }
  }

  @Test
  void closedExternalViewCannotDelegateAndCatchCannotCommit() throws Exception {
    var fixture = new SessionHistoryLengthTest.Fixture();
    var query = SessionEventHistoryLengthTest.query();
    RuntimeException[] first = {null};
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.work(
                    query,
                    () -> {
                      try {
                        var provider = new Provider(fixture, query);
                        provider.open();
                        var view = provider.rows.getMetaData();
                        provider.rows.close();
                        provider.detached(true);
                        int calls = provider.getters;
                        first[0] = assertThrows(RuntimeException.class, view::getColumnCount);
                        assertTrue(first[0].getCause() instanceof SQLException);
                        assertSame(first[0], query.recordedFailure());
                        assertEquals(calls, provider.getters);
                        return null;
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }));
    assertSame(first[0], thrown);
    assertEquals(0, fixture.database.commits);
    assertEquals(1, fixture.database.rollbacks);
    assertEquals(65536, query.scratchLive());
  }

  @Test
  void constructorMetadataFailureClosesProviderAndDropsSlotAliases() throws Exception {
    var fixture = new SessionHistoryLengthTest.Fixture();
    var query = SessionEventHistoryLengthTest.query();
    Provider[] saved = {null};
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.work(
                    query,
                    () -> {
                      try {
                        var provider = new Provider(fixture, query);
                        saved[0] = provider;
                        provider.failMetadata = true;
                        var first = assertThrows(RuntimeException.class, provider::open);
                        assertSame(first, query.recordedFailure());
                        assertNull(field(provider.slot, "sizeStatement"));
                        assertNull(field(provider.slot, "sizeResult"));
                        return null;
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }));
    assertSame(saved[0].read, thrown.getCause());
    assertEquals(1, saved[0].closes);
    assertEquals(1, fixture.database.rollbacks);
    assertEquals(65536, query.scratchLive());
  }
}
