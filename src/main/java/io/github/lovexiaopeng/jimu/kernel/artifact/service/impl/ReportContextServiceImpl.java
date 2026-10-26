package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportContexts;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ReportContextService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 原 leaseTx/fence、双 tools 读取及逐行 pin 短路；空来源不提前验 pin。 */
public final class ReportContextServiceImpl implements ReportContextService {
  private final ReportContexts records;

  public ReportContextServiceImpl(ReportContexts records) {
    this.records = records;
  }

  public ReportContext context(Lease lease) {
    return records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          if (!task.publicReport() || task.time() == null)
            throw new TaskFailure(409, "REPORT_METADATA_MISSING");
          return context(task);
        });
  }

  public ReportContext context(ReportContexts.Task task) {
    boolean composed = task.tools().pins().containsKey("compose_report");
    var fetchPin = task.tools().pins().get("web_fetch");
    var sources =
        records.sources(task, composed).stream()
            .map(
                row -> {
                  if (composed
                      && (fetchPin == null
                          || !"0.3.0".equals(fetchPin.version())
                          || !fetchPin.version().equals(row.value("plugin_version"))
                          || !fetchPin.digest().equals(row.value("plugin_digest"))))
                    throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
                  return new Source(
                      row.value("id"), row.value("output_snapshot"), row.value("output_digest"));
                })
            .toList();
    return new ReportContext(task.time(), sources);
  }
}
