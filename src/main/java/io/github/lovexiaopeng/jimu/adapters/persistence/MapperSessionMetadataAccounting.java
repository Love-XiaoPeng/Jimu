package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionContextMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Original Mapper statements and raw row values, read one table/row at a time. */
final class MapperSessionMetadataAccounting
    implements SessionMetadataAccounting,
        SessionMetadataAccounting.FinalRows,
        io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults.FinalMetadata {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final boolean finalPresent;

  MapperSessionMetadataAccounting(JdbcSessionRecords records, Map<String, Object> task) {
    this(records, task, false);
  }

  MapperSessionMetadataAccounting(
      JdbcSessionRecords records, Map<String, Object> task, boolean finalPresent) {
    this.finalPresent = finalPresent;
    this.records = records;
    this.task = task;
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public Batch batch(Table table) {
    if (table == Table.TASK && JdbcSessionScopes.lineage(task))
      return new SessionCursorMetadataBatch(
          records.appendQuery(),
          SessionMetadataProjection.taskSized(),
          () -> records.mapperCursor("SessionHistoryRetentionMapper.metadataTaskSized", task));
    var projection = SessionMetadataProjection.forTable(table);
    if (projection != null && JdbcSessionScopes.lineage(task))
      return new SessionCursorMetadataBatch(
          records.appendQuery(), projection, () -> records.mapperCursor(operation(table), task));
    if (table == Table.TASK_SESSION_HISTORY && JdbcSessionScopes.lineage(task))
      return records.historyMetadataBatch(task);
    if (table != Table.TASK_SESSION_LINEAGE || !JdbcSessionScopes.lineage(task))
      return SessionMetadataAccounting.super.batch(table);
    return new SessionLineageMetadataBatch(
        records.appendQuery(),
        () -> records.mapperRows("SessionHistoryRetentionMapper.metadataTaskSessionLineage", task));
  }

  private io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext finalQuery;
  private io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork finalWork;
  private io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork.ProjectionLock
      finalLock;
  private String finalTenant, finalTask;
  private Object finalOwner;
  private Map<String, Object> finalParameters;
  private boolean finalActive, finalClosed;

  private long finalCheck() {
    var query = records.appendQuery();
    if (query != null) query.operation(7); // query/scope fields, comparisons and version handoff
    if (!finalActive || finalClosed || query != finalQuery)
      throw new IllegalStateException("Inactive or foreign metadata finalization");
    if (finalWork != null) return finalWork.metadataFinalVersion(query);
    if (io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner()
        != null) throw new IllegalStateException("Foreign metadata finalization");
    if (query != null) query.checkHealthy();
    return 0;
  }

  private void finalFailure(Throwable failure) {
    var actual =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (finalWork != null) {
      if (failure instanceof Error error) finalWork.poisonLineageSource(error);
      else finalWork.poisonProof((RuntimeException) failure);
    }
    if (actual != null && actual != finalWork) {
      if (failure instanceof Error error) actual.poisonLineageSource(error);
      else actual.poisonProof((RuntimeException) failure);
    }
  }

  private FinalRows finalConsumer;
  private boolean ordinaryRead;
  private long ordinaryVersion;
  private long[] ordinaryCounts, ordinarySizes;

  public void accept(
      Table table,
      long rows,
      long bytes,
      long histories,
      HistoryScalars history,
      long reservations,
      HistoryReservation reservation) {
    if (readingCommand != null && readingTable == null) {
      // Keep consumeFinal's per-table contract; only these two bounded arrays survive the Result.
      // Their storage is covered by finalOwner's original 512-byte summary allowance.
      finalQuery.operation(6);
      ordinaryCounts[table.ordinal()] = rows;
      ordinarySizes[table.ordinal()] = bytes;
      return;
    }
    // Real service work: empty returns immediately; nonempty totals and only relevant special
    // fields.
    finalQuery.operation(
        rows == 0 ? 2 : 16 + (history == null ? 0 : 18) + (reservation == null ? 0 : 10));
    finalConsumer.accept(table, rows, bytes, histories, history, reservations, reservation);
  }

  public boolean consumeFinal(Table table, FinalRows consumer) {
    if (!finalPresent || !JdbcSessionScopes.lineage(task)) return false;
    return consumeTable(table, consumer);
  }

  public boolean consumeMetadata(FinalRows consumer) {
    var query = records.appendQuery();
    if (query == null || !query.lineage() || !JdbcSessionScopes.lineage(task)) return false;
    finalCheck();
    // 接收对象/两标量、values数组/18表路由及scope返回清理均在当前Work预付。
    finalQuery.operation(64);
    for (var table : Table.values()) consumeTable(table, consumer);
    return true;
  }

  private boolean consumeTable(Table table, FinalRows consumer) {
    try {
      long version = finalCheck();
      finalQuery.operation(9); // route, receiver store/check and finally clear; no summary readout
      if (finalConsumer != null) throw new IllegalStateException("Nested FINAL consumer");
      finalConsumer = consumer;
      FinalTable result;
      finalQuery.operation(8); // three special-table tests, summary state/version and stores
      if (table != Table.TASK
          && table != Table.TASK_SESSION_HISTORY
          && table != Table.RESERVATION) {
        if (!ordinaryRead) {
          finalQuery.operation(48); // both arrays, zeroing, field stores and scope cleanup
          ordinaryCounts = new long[18];
          ordinarySizes = new long[18];
          readFinal(null);
          ordinaryVersion = version;
          ordinaryRead = true;
        } else if (ordinaryVersion != version)
          throw new IllegalStateException("Write after ordinary metadata read");
        finalQuery.operation(8);
        accept(
            table,
            ordinaryCounts[table.ordinal()],
            ordinarySizes[table.ordinal()],
            0,
            null,
            0,
            null);
        return true;
      }
      if (table != Table.TASK_SESSION_HISTORY) {
        readFinal(table);
        return true;
      }
      result =
          io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistorySizedMetadataBatch
              .measureFinal(
                  records.mapperSession(), finalQuery, s(task, "tenant_id"), s(task, "id"));
      finalCheck();
      accept(
          table,
          result.rows(),
          result.bytes(),
          result.histories(),
          result.history(),
          result.reservations(),
          result.reservation());
      finalCheck();
      return true;
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
      throw failure;
    } finally {
      finalConsumer = null;
    }
  }

  private Table readingTable;
  private org.apache.ibatis.mapping.MappedStatement readingCommand;
  private long readingVersion;
  private boolean readingConsumed;

  private void readFinal(Table table) {
    // 原逐表参数构造/两次Map取身份改为作用域内一次；保留命令查找、分派及finally清理。
    finalQuery.operation(16);
    if (readingCommand != null) throw new IllegalStateException("Nested FINAL result");
    finalQuery.operation(6); // current task Map still holds the identities bound at finalization
    if (s(task, "tenant_id") != finalTenant || s(task, "id") != finalTask)
      throw new IllegalStateException("Changed FINAL task identity");
    finalWork.requireProjectionLocks(finalQuery, finalLock);
    if (finalParameters == null) {
      // 外层owner已保留Map及三条引用；初始化及close/失败断引用全部在当前Work预付。
      finalQuery.operation(28);
      finalParameters =
          Map.of(
              "query",
              finalQuery,
              "tenant",
              finalTenant,
              "taskId",
              finalTask,
              "nullBytes",
              sessionNullBytes());
    }
    var session = records.mapperSession();
    var command =
        session
            .getConfiguration()
            .getMappedStatement(
                table == null
                    ? "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadataOrdinaryRows"
                    : table == Table.TASK
                        ? "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadataTaskSizedRow"
                        : SessionMetadataProjection.forFinalTable(table).rowStatement);
    readingTable = table;
    readingCommand = command;
    try {
      finalQuery.operation(4);
      if (!finalParameters.get("nullBytes").equals(sessionNullBytes()))
        throw new IllegalStateException("Changed metadata NULL policy");
      io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryMetadataLanguageDriver
          .verifyRowCommand(
              command, table == null ? -2 : table.ordinal(), finalQuery, finalParameters);
      if (table == null) {
        finalQuery.operation(12);
        finalQuery.retainScratch(
            command, 8192 + 4L * command.getBoundSql(finalParameters).getSql().length());
      }
      readingVersion = finalWork.metadataReadVersion(finalQuery);
      session.select(command.getId(), finalParameters, this);
      if (readingVersion != finalCheck())
        throw new IllegalStateException("Write before FINAL result closed");
      if (!readingConsumed) throw new IllegalStateException("Missing FINAL consumption");
    } finally {
      if (table == null) finalQuery.releaseScratch(command);
      readingTable = null;
      readingCommand = null;
      readingConsumed = false;
      readingVersion = 0;
    }
  }

  private Long sessionNullBytes() {
    return records.mapperSession().getConfiguration().isCallSettersOnNulls() ? 9L : 0L;
  }

  @Override
  public void handleResult(org.apache.ibatis.session.ResultContext<?> ignored) {
    throw new IllegalStateException("FINAL result must use its actual ResultSet");
  }

  @Override
  public void read(
      java.sql.ResultSet rows,
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext context,
      org.apache.ibatis.mapping.MappedStatement command)
      throws java.sql.SQLException {
    try {
      long version = finalCheck();
      finalQuery.operation(8); // command/query identity, single read and receiver store
      if (context != finalQuery
          || command != readingCommand
          || readingCommand == null
          || readingConsumed) throw new IllegalStateException("Foreign FINAL result");
      if (readingVersion != version)
        throw new IllegalStateException("Write before FINAL result consumption");
      if (readingTable == null)
        SessionCursorMetadataBatch.consumeOrdinaryResultSet(finalQuery, rows, command, this);
      else if (readingTable == Table.TASK)
        SessionCursorMetadataBatch.consumeTaskRowResultSet(
            finalQuery,
            rows,
            command,
            records.mapperSession().getConfiguration().isCallSettersOnNulls(),
            this);
      else
        SessionCursorMetadataBatch.consumeRowResultSet(
            finalQuery,
            readingTable,
            rows,
            command,
            records.mapperSession().getConfiguration().isCallSettersOnNulls(),
            this);
      readingConsumed = true;
      if (readingVersion != finalWork.metadataReadVersion(finalQuery))
        throw new IllegalStateException("Write during FINAL result consumption");
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
      throw failure;
    } catch (java.sql.SQLException failure) {
      finalFailure(
          new org.springframework.dao.DataAccessResourceFailureException(
              "FINAL metadata unavailable", failure));
      throw failure;
    }
  }

  public Balance accountAndReadBack(long observed, long desired) {
    try {
      finalCheck();
      if (finalQuery != null)
        finalQuery.operation(20); // branch, call handoffs, eight final comparisons/clear.
      if (observed != desired) account(desired);
      finalCheck();
      var balance = readBack(); // Always a fresh SELECT, also when no UPDATE is needed.
      finalCheck();
      return balance;
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
      throw failure;
    }
  }

  public Iterable<? extends Row> rows(Table table) {
    if ((table == Table.TASK_SESSION_LINEAGE || table == Table.INVOCATION_SESSION_LINEAGE_TRACE)
        && !JdbcSessionScopes.lineage(task)) return java.util.List.of();
    var rows = records.mapperRows(operation(table), task);
    return () ->
        new Iterator<Row>() {
          private final Iterator<Map<String, Object>> iterator = rows.iterator();

          public boolean hasNext() {
            return iterator.hasNext();
          }

          public Row next() {
            var row = iterator.next();
            return new Row() {
              public Iterable<?> values() {
                return row.values();
              }

              public Object value(String name) {
                return row.get(name);
              }
            };
          }
        };
  }

  private static String operation(Table table) {
    return "SessionHistoryRetentionMapper.metadata"
        + java.util.Arrays.stream(table.name().toLowerCase(Locale.ROOT).split("_"))
            .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
            .collect(java.util.stream.Collectors.joining());
  }

  public boolean finalPresent() {
    return finalPresent;
  }

  public io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext.MeterScope
      finalization() {
    var routeQuery = records.appendQuery();
    if (routeQuery != null)
      routeQuery.operation(5); // preflight routing/query/lineage and existing scope dispatch
    if (!finalPresent
        && (routeQuery == null || !routeQuery.lineage() || !JdbcSessionScopes.lineage(task)))
      return SessionMetadataAccounting.super.finalization();
    if (finalActive || finalClosed) {
      var failure = new IllegalStateException("Metadata finalization already consumed");
      finalFailure(failure);
      throw failure;
    }
    try {
      finalQuery = routeQuery;
      finalWork =
          io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
              .currentOwner();
      if (JdbcSessionScopes.lineage(task)
          && (finalQuery == null
              || !finalQuery.lineage()
              || finalWork == null
              || io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                      .current(finalQuery)
                  != finalWork)) throw new IllegalStateException("Missing final metadata Work");
      finalOwner = new Object();
      // summary512 + balance/row overlap1024 + fixed-point/formatter2048 + closure/locals512.
      if (finalQuery != null) {
        finalQuery.operation(40); // FinalTotals allocation/fields/readout and consumer lifecycle
        // 原4120 + 不可变三参数Map/entry保守128 + 字段及query/tenant/task别名32。
        finalQuery.retainScratch(
            finalOwner, 4568); // retain the additional immutable NULL-policy entry/alias as well
        if (finalWork != null && JdbcSessionScopes.lineage(task)) {
          finalTenant = s(task, "tenant_id");
          finalTask = s(task, "id");
          finalLock = finalWork.projectionLock(finalQuery, finalTenant, finalTask);
        }
      }
      finalActive = true;
      finalCheck();
      return () -> {
        if (!finalActive) return;
        var query = finalQuery;
        var owner = finalOwner;
        finalActive = false;
        finalClosed = true;
        finalOwner = null;
        finalParameters = null;
        ordinaryCounts = null;
        ordinarySizes = null;
        finalQuery = null;
        finalWork = null;
        finalLock = null;
        finalTenant = null;
        finalTask = null;
        if (query != null) query.releaseScratch(owner);
      };
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
      if (finalQuery != null && finalOwner != null) finalQuery.releaseScratch(finalOwner);
      finalOwner = null;
      finalParameters = null;
      ordinaryCounts = null;
      ordinarySizes = null;
      finalQuery = null;
      finalWork = null;
      finalLock = null;
      finalTenant = null;
      finalTask = null;
      finalActive = false;
      finalClosed = true;
      throw failure;
    }
  }

  public Balance readBack() {
    var rows =
        records
            .mapperSession()
            .getMapper(SessionContextMapper.class)
            .balance(records.appendQuery(), s(task, "tenant_id"), s(task, "id"));
    if (rows.size() != 1) throw JdbcSessionRecords.corrupt();
    var row = rows.getFirst();
    return new Balance(
        n(row, "metadata_bytes"),
        n(row, "body_bytes"),
        n(row, "reserved_bytes"),
        n(row, "held_bytes"),
        n(row, "amount"),
        row.get("actual") == null ? null : n(row, "actual"),
        s(row, "reservation_state"),
        s(row, "content_state"));
  }

  public long metadataBytes() {
    return n(
        records.mapperRows("SessionContextMapper.metadataBytes", task).getFirst(),
        "metadata_bytes");
  }

  public void account(long bytes) {
    int changed =
        records
            .mapperSession()
            .getMapper(SessionContextMapper.class)
            .account(records.appendQuery(), bytes, s(task, "tenant_id"), s(task, "id"));
    if (finalPresent && changed != 1) throw JdbcSessionRecords.corrupt();
  }
}
