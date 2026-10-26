package io.github.lovexiaopeng.jimu.kernel.execution.service;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords.Admission;

/** 原UoW内的模块请求、summary与媒体检查点准入和结算。 */
public interface ExecutionAdmissionService {
  void lease(
      io.github.lovexiaopeng.jimu.kernel.execution.port.LeaseAdmission facts,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease);

  void request(Admission task, String request);

  void media(Admission task, String step, String request);

  void summary(Admission task, String summary);
}
