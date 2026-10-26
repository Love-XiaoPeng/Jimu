package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportContexts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 惰性原行适配及两个固定 SQL 形状，同 DS/UoW/query；不解码或复制 task。 */
final class MapperReportContexts implements ReportContexts {
  private final JdbcLedger ledger;

  MapperReportContexts(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public <T> T transaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public Task fenced(Lease lease, boolean admission) {
    return wrap(ledger.fenced(lease, admission));
  }

  Task wrap(Map<String, Object> row) {
    return new TaskRow(row);
  }

  private final class TaskRow implements Task {
    private final Map<String, Object> row;

    private TaskRow(Map<String, Object> row) {
      this.row = row;
    }

    public boolean publicReport() {
      return ledger.publicReport(row);
    }

    public Instant time() {
      return JdbcLedger.time(row, "report_time");
    }

    public ToolSnapshot tools() {
      return ledger.tools(row);
    }
  }

  public List<? extends SourceRow> sources(Task task, boolean composed) {
    var row = ((TaskRow) task).row;
    var rows =
        composed
            ? ledger
                .mappers
                .invocations()
                .reportComposedSources(
                    ledger.queryContext(), JdbcLedger.s(row, "tenant_id"), JdbcLedger.s(row, "id"))
            : ledger
                .mappers
                .invocations()
                .reportOrdinarySources(
                    ledger.queryContext(), JdbcLedger.s(row, "tenant_id"), JdbcLedger.s(row, "id"));
    return new AbstractList<SourceRow>() {
      public int size() {
        return rows.size();
      }

      public SourceRow get(int index) {
        var source = rows.get(index);
        return column -> JdbcLedger.s(source, column);
      }
    };
  }
}
