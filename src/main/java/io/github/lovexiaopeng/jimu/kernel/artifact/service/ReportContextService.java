package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportContexts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;

/** 公开和内部报告来源资格共用原有完整规则。 */
public interface ReportContextService {
  ReportContext context(Lease lease);

  ReportContext context(ReportContexts.Task task);
}
