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

  MapperSessionMetadataAccounting(JdbcSessionRecords records, Map<String, Object> task) {
    this.records = records;
    this.task = task;
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public Iterable<? extends Row> rows(Table table) {
    if ((table == Table.TASK_SESSION_LINEAGE || table == Table.INVOCATION_SESSION_LINEAGE_TRACE)
        && !JdbcSessionScopes.lineage(task)) return java.util.List.of();
    String operation =
        "SessionHistoryRetentionMapper.metadata"
            + java.util.Arrays.stream(table.name().toLowerCase(Locale.ROOT).split("_"))
                .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
                .collect(java.util.stream.Collectors.joining());
    var rows = records.mapperRows(operation, task);
    return () ->
        new Iterator<Row>() {
          private final Iterator<Map<String, Object>> iterator = rows.iterator();

          public boolean hasNext() {
            return iterator.hasNext();
          }

          public Row next() {
            var row = iterator.next();
            return () -> row.values();
          }
        };
  }

  public long metadataBytes() {
    return n(
        records.mapperRows("SessionContextMapper.metadataBytes", task).getFirst(),
        "metadata_bytes");
  }

  public void account(long bytes) {
    records
        .mapperSession()
        .getMapper(SessionContextMapper.class)
        .account(records.appendQuery(), bytes, s(task, "tenant_id"), s(task, "id"));
  }
}
