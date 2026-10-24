package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReceipts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReceipts.Field;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionReceiptService;
import java.util.List;

/** 显式保留字段顺序、惰性读取/异常顺序以及原节点类型；不增加持久化操作。 */
public final class SessionReceiptServiceImpl implements SessionReceiptService {
  public void nativeReceipt(SessionReceipts.Native p) {
    p.number(Field.SEQ, p.target());
    p.number(Field.BEFORE, p.before());
    p.number(Field.AFTER, p.after());
    p.number(Field.UPPER, p.upper());
    finish(p);
  }

  public void scopeReceipt(SessionReceipts.Scope p) {
    p.parseNormalized();
    for (var field : List.of(Field.SEQ, Field.BEFORE, Field.AFTER, Field.UPPER, Field.TARGET))
      p.retainNode(field);
    finish(p);
  }

  private static void finish(SessionReceipts.Common p) {
    p.text(Field.OUTCOME, p.outcome());
    p.text(Field.CODE, p.code());
    p.text(Field.DIGEST, p.digest());
    p.number(Field.BYTES, p.bytes());
  }
}
