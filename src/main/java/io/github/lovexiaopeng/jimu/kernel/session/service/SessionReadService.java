package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** 原生会话读取、已提交结果回放与回执核验。 */
public interface SessionReadService {
  String readOrResult(Lease lease, String step, Call call, String version, String digest);
}
