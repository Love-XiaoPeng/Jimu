package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionContextMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Original Mapper statements and raw row values, read one table/row at a time. */
final class MapperSessionMetadataAccounting implements SessionMetadataAccounting {
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
  private Object finalOwner;
  private boolean finalActive, finalClosed;

  private void finalCheck() {
    var query = records.appendQuery();
    if (query != null) query.operation(12);
    var actual =
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
            .currentOwner();
    if (!finalActive
        || finalClosed
        || query != finalQuery
        || actual != finalWork
        || (actual != null
            && io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction
                    .current(query)
                != actual))
      throw new IllegalStateException("Inactive or foreign metadata finalization");
    if (query != null) query.checkHealthy();
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

  public FinalTable finalTable(Table table) {
    if (!finalPresent || !JdbcSessionScopes.lineage(task)) return null;
    try {
      finalCheck();
      // Route plus service's summary fold, headroom, special-field handoff and temporary clear.
      finalQuery.operation(32);
      FinalTable result;
      if (table == Table.TASK)
        result =
            SessionCursorMetadataBatch.measureFinal(
                finalQuery,
                table,
                SessionMetadataProjection.taskSized(),
                () ->
                    records.mapperCursor("SessionHistoryRetentionMapper.metadataTaskSized", task));
      else if (table == Table.TASK_SESSION_HISTORY)
        result =
            io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistorySizedMetadataBatch
                .measureFinal(
                    records.mapperSession(), finalQuery, s(task, "tenant_id"), s(task, "id"));
      else if (table == Table.TASK_SESSION_LINEAGE)
        result =
            SessionLineageMetadataBatch.measureFinal(
                finalQuery,
                () ->
                    records.mapperRows(
                        "SessionHistoryRetentionMapper.metadataTaskSessionLineage", task));
      else {
        var projection = SessionMetadataProjection.forTable(table);
        if (projection == null)
          throw new IllegalStateException("Missing final metadata projection");
        result =
            SessionCursorMetadataBatch.measureFinal(
                finalQuery, table, projection, () -> records.mapperCursor(operation(table), task));
      }
      finalCheck();
      return result;
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
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
    if (finalActive || finalClosed) {
      var failure = new IllegalStateException("Metadata finalization already consumed");
      finalFailure(failure);
      throw failure;
    }
    try {
      finalQuery = records.appendQuery();
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
        finalQuery.operation(12);
        finalQuery.retainScratch(finalOwner, 4096);
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
        finalQuery = null;
        finalWork = null;
        if (query != null) query.releaseScratch(owner);
      };
    } catch (RuntimeException | Error failure) {
      finalFailure(failure);
      if (finalQuery != null && finalOwner != null) finalQuery.releaseScratch(finalOwner);
      finalOwner = null;
      finalQuery = null;
      finalWork = null;
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
