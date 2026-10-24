package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReceipts;

/** 原生和scope read冻结receipt的共享装配用例。 */
public interface SessionReceiptService {
  void nativeReceipt(SessionReceipts.Native values);

  void scopeReceipt(SessionReceipts.Scope values);
}
