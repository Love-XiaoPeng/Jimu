package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** 原槽查询／回放、receipt证明与原子结算；已知拒绝在提交后抛出。 */
public interface SessionScopeQueryService {
  String scopedOrResult(Lease lease, String step, Call call, String version, String digest);
}
