package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionResponsePreflight;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import java.util.List;

/** 受理响应前校验容量、槽位、输入及完整公开事件。 */
public interface SessionResponseService {
  void validate(SessionResponsePreflight storage, String response, List<Call> calls);
}
