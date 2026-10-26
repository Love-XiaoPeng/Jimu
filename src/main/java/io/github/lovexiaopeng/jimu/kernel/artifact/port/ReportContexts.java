package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** 报告原始来源行及惰性资格读取，复用调用者事务和原 Map 引用。 */
public interface ReportContexts {
  interface Task {
    boolean publicReport();

    Instant time();

    ToolSnapshot tools();
  }

  interface SourceRow {
    String value(String column);
  }

  <T> T transaction(Lease lease, Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  List<? extends SourceRow> sources(Task task, boolean composed);
}
