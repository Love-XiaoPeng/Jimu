package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Input;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Stored;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeNormalizationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;

/** 保留codec/缺省/upper/资格顺序及原始存储字节证明，不合并资格读取。 */
public final class SessionScopeNormalizationServiceImpl
    implements SessionScopeNormalizationService {
  public String normalize(Input input) {
    if (input.tool().equals(ToolCapability.SESSION_SEARCH)) {
      String query = input.searchQuery();
      var object = input.searchObject();
      object.query(query);
      return object.sortedJson();
    }
    var read = input.read();
    String target = read.session() == null ? input.caller() : read.session();
    long upper = input.upper();
    var scope = input.requireScope();
    if (!target.equals(input.caller())) upper = scope.upper(target);
    return input.writeRead(target, read, upper);
  }

  public void verify(String expected, Stored stored, Proof proof) {
    if (!proof.sameJson(expected, stored.raw())
        || !proof.hash(stored.raw()).equals(stored.digest().get())
        || proof.bytes(stored.raw()) != stored.bytes().getAsLong())
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }
}
