package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Faults wrap the real Connector result, never replace its rows or successful fallback. */
final class SessionFinalMapperFailures {
  private static final String ID = "7bde5bee-94a6-49ef-a663-dc1c8488c8cc";

  private static Object invoke(Object object, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(object, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  static final class FaultSource extends DelegatingDataSource {
    String table = "task_attempt";
    String sqlContains;
    String matchedSql;
    String fault;
    Runnable write;
    boolean changed, closed;
    int getters, statements;
    Throwable primary, secondary;
    ResultSet providerRows;
    final List<Object> binds = new ArrayList<>();

    FaultSource(DataSource source) {
      super(source);
    }

    @Override
    public Connection getConnection() throws SQLException {
      var connection = super.getConnection();
      return proxy(
          Connection.class,
          (o, m, a) -> {
            Object value = invoke(connection, m, a);
            if (!m.getName().equals("prepareStatement")
                || !(value instanceof PreparedStatement statement)) return value;
            String sql = a[0].toString();
            if (!sql.contains("FROM " + table + " ")
                || (sqlContains != null && !sql.contains(sqlContains))) {
              ResultSet[] provider = {null}, wrapped = {null};
              return proxy(
                  PreparedStatement.class,
                  (p, sm, sa) -> {
                    if (sm.getName().equals("getConnection")) return o;
                    Object result = invoke(statement, sm, sa);
                    if (result instanceof ResultSet rows) {
                      if (provider[0] != rows) {
                        provider[0] = rows;
                        wrapped[0] =
                            proxy(
                                ResultSet.class,
                                (r, rm, ra) ->
                                    rm.getName().equals("getStatement") ? p : invoke(rows, rm, ra));
                      }
                      return wrapped[0];
                    }
                    return result;
                  });
            }
            statements++;
            matchedSql = sql;
            return proxy(
                PreparedStatement.class,
                (p, sm, sa) -> {
                  if ((table.equals("step") || table.equals("task"))
                      && sm.getName().equals("getConnection"))
                    return fault.equals("connection") ? connection : o;
                  if (sm.getName().equals("setString")) binds.add(sa[1]);
                  Object output = invoke(statement, sm, sa);
                  if ((table.equals("step") || table.equals("task"))
                      && sm.getName().equals("close")
                      && providerRows != null) closed = providerRows.isClosed();
                  if (!sm.getName().equals("getResultSet") || !(output instanceof ResultSet rows))
                    return output;
                  providerRows = rows;
                  return proxy(
                      ResultSet.class,
                      (r, rm, ra) -> {
                        if ((table.equals("step") || table.equals("task"))
                            && rm.getName().equals("getStatement")) return p;
                        if (rm.getName().equals("getMetaData")) {
                          var metadata = rows.getMetaData();
                          if (table.equals("task") && fault.equals("metadata-write") && !changed) {
                            changed = true;
                            write.run();
                          }

                          if (table.equals("task")
                              && fault.startsWith("source-")
                              && (!changed || fault.equals("source-count"))) {
                            changed = true;
                            var mysql = (com.mysql.cj.jdbc.result.ResultSetMetaData) metadata;
                            if (fault.equals("source-count"))
                              return new com.mysql.cj.jdbc.result.ResultSetMetaData(
                                  null,
                                  java.util.Arrays.copyOf(
                                      mysql.getFields(), mysql.getFields().length - 1),
                                  false,
                                  false,
                                  null);
                            var field =
                                mysql
                                    .getFields()[
                                    fault.equals("source-charset")
                                            || fault.equals("source-encoding")
                                        ? 2
                                        : 20];
                            if (fault.equals("source-type"))
                              field.setMysqlType(com.mysql.cj.MysqlType.BLOB);
                            else {
                              String member =
                                  switch (fault) {
                                    case "source-charset" -> "collationIndex";
                                    case "source-encoding" -> "encoding";
                                    case "source-table" -> "originalTableName";
                                    default -> "originalColumnName";
                                  };
                              var f = com.mysql.cj.result.Field.class.getDeclaredField(member);
                              f.setAccessible(true);
                              f.set(
                                  field,
                                  member.equals("collationIndex")
                                      ? 8
                                      : member.equals("encoding")
                                          ? "windows-1252"
                                          : new com.mysql.cj.util.LazyString("foreign"));
                            }
                            return metadata;
                          }
                          if ((table.equals("step") || table.equals("task"))
                              && !List.of("count", "label", "type").contains(fault))
                            return metadata;
                          return proxy(
                              ResultSetMetaData.class,
                              (m0, mm, ma) -> {
                                if (fault.equals("metadata-write")
                                    && !changed
                                    && mm.getName().equals("getColumnLabel")) {
                                  changed = true;
                                  write.run();
                                }
                                if (fault.equals("count") && mm.getName().equals("getColumnCount"))
                                  return metadata.getColumnCount() + 1;
                                if (fault.equals("label") && mm.getName().equals("getColumnLabel"))
                                  return "wrong";
                                if (fault.equals("type") && mm.getName().equals("getColumnType"))
                                  return java.sql.Types.BLOB;
                                return invoke(metadata, mm, ma);
                              });
                        }
                        if (rm.getName().equals("getObject")) {
                          getters++;
                          if (fault.equals("error")) throw primary;
                          if (fault.equals("write") && !changed) {
                            changed = true;
                            write.run();
                          }
                        }
                        Object outputRow = invoke(rows, rm, ra);
                        if (fault.equals("next-write") && !changed && rm.getName().equals("next")) {
                          changed = true;
                          write.run();
                        }
                        if (rm.getName().equals("close")) {
                          closed = true;
                          if (fault.equals("close-write") && !changed) {
                            changed = true;
                            write.run();
                          }
                          if (secondary != null) throw secondary;
                          if (fault.equals("close")) throw primary;
                        }
                        return outputRow;
                      });
                });
          });
    }
  }

  static void verify(DataSource plain) {
    verify(plain, ID, false);
  }

  static void verify(DataSource plain, String taskId, boolean preflight) {
    verify(plain, taskId, preflight, Table.TASK_ATTEMPT);
  }

  static void verify(DataSource plain, String taskId, boolean preflight, Table target) {
    verify(plain, taskId, preflight, target, false);
  }

  static void verifyBoundaries(DataSource plain, String taskId, Table target) {
    verify(plain, taskId, false, target, true);
  }

  private static void verify(
      DataSource plain, String taskId, boolean preflight, Table target, boolean boundariesOnly) {
    var source = new FaultSource(plain);
    var db = new JdbcTemplate(source);
    var mappers = LedgerMapperSessions.create(source);
    var tx = new SessionQueryTransaction(source, new DataSourceTransactionManager(source));
    var task =
        Map.<String, Object>of(
            "tenant_id",
            "tenant-a",
            "id",
            taskId,
            "capabilities",
            SessionLineagePolicyV5.CAPABILITIES);
    var faults =
        new ArrayList<>(
            List.of(
                "count",
                "label",
                "type",
                "write",
                "metadata-write",
                "next-write",
                "extra",
                "close-write",
                "error",
                "close",
                "foreign",
                "parameter"));
    if (preflight) faults.add("history-extra");
    if (boundariesOnly)
      faults.retainAll(List.of("write", "metadata-write", "next-write", "extra", "close-write"));
    for (String fault : faults) {
      source.table = "task_attempt";
      source.fault = fault;
      source.changed = false;
      source.closed = false;
      source.getters = 0;
      source.statements = 0;
      source.binds.clear();
      source.primary =
          fault.equals("error")
              ? new AssertionError("actual getter Error")
              : new SQLException("actual close failure");
      source.secondary = fault.equals("error") ? new SQLException("secondary close failure") : null;
      var q =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      q.restrictLineage();
      var foreign =
          new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      foreign.restrictLineage();
      var records = new JdbcSessionRecords(db, fault.equals("foreign") ? foreign : q, mappers);
      var accounting = new MapperSessionMetadataAccounting(records, task, !preflight);
      var beforeTables = preflight ? SessionDependencyScenario.snapshot(db) : null;
      long before =
          db.queryForObject(
              "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
              Long.class,
              taskId);
      source.write =
          () ->
              mappers
                  .getMapper(
                      io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                          .SessionContextMapper.class)
                  .account(q, before + 1, "tenant-a", taskId);
      source.table =
          fault.equals("history-extra")
              ? "task_session_history"
              : target.name().toLowerCase(java.util.Locale.ROOT);
      Throwable caught = null;
      try {
        tx.required(
            q,
            () -> {
              SessionQueryTransaction.current(q).locked("tenant-a", taskId);
              try (var meter =
                  q.meter(
                      () ->
                          (label, value) -> {
                            if (List.of("extra", "history-extra").contains(fault)
                                && source.statements > 0
                                && !source.changed) {
                              source.changed = true;
                              source.write.run();
                            }
                          })) {
                if (preflight && !fault.equals("parameter")) {
                  new io.github.lovexiaopeng.jimu.kernel.session.service.impl
                          .SessionMetadataAccountingServiceImpl()
                      .metadata(accounting, false, 0);
                  return null;
                }
                try (var scope = accounting.finalization()) {
                  if (fault.equals("parameter"))
                    mappers.select(
                        "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadataTaskAttempt",
                        Map.of("query", foreign, "tenant", "tenant-a", "taskId", taskId),
                        accounting);
                  else SessionFinalResultFoldTest.table(accounting, target);
                }
              }
              return null;
            });
      } catch (RuntimeException | Error failure) {
        caught = failure;
      }
      if (caught == null) throw new AssertionError("FINAL fault accepted " + fault);
      if (List.of("write", "extra", "history-extra").contains(fault) && source.getters != 1)
        throw new AssertionError("Getter after dynamic refusal " + fault + ": " + source.getters);
      if (List.of("metadata-write", "next-write").contains(fault) && source.getters != 0)
        throw new AssertionError("Getter after driver metadata/next write " + fault);
      if (fault.equals("count") || fault.equals("label") || fault.equals("type"))
        if (source.getters != 0) throw new AssertionError("Getter before static refusal " + fault);
      if (fault.equals("foreign") || fault.equals("parameter")) {
        if (source.statements != 0) throw new AssertionError("Foreign SQL executed");
      } else if (source.statements != 1
          || !source.binds.equals(List.of("tenant-a", taskId))
          || !source.closed)
        throw new AssertionError("SQL/parameter/close changed " + fault + source.binds);
      if (fault.equals("error")
          && (!contains(caught, source.primary) || !contains(source.primary, source.secondary)))
        throw new AssertionError("Error/close primary lost", caught);
      if (fault.equals("close") && !contains(caught, source.primary))
        throw new AssertionError("Close failure lost", caught);
      if (q.scratchLive() != 65536 || SessionQueryTransaction.currentOwner() != null)
        throw new AssertionError("Owner leaked " + fault);
      if (before
          != db.queryForObject(
              "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
              Long.class,
              taskId)) throw new AssertionError("Write not rolled back " + fault);
      if (preflight && !beforeTables.equals(SessionDependencyScenario.snapshot(db)))
        throw new AssertionError("Preflight22-table rollback failed " + fault);
      System.out.println(
          (preflight ? "PASS PREFLIGHT_REAL_FAULT " : "PASS FINAL_REAL_FAULT ")
              + fault
              + " getters="
              + source.getters
              + " first="
              + caught.getClass().getSimpleName());
      System.out.flush();
    }
  }

  static boolean contains(Throwable root, Throwable expected) {
    if (root == expected) return true;
    if (root.getCause() != null && root.getCause() != root && contains(root.getCause(), expected))
      return true;
    for (var extra : root.getSuppressed()) if (contains(extra, expected)) return true;
    return false;
  }
}
