package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Validated;

/** 受理、运行及维护共用当前scope资格和有序锁规则。 */
public interface SessionScopeEligibilityService {
  void lock(String tenant, String caller);

  void lock(String tenant, java.util.List<String> ids);

  Authority snapshot(Caller caller, Scope scope);

  Validated require(Caller caller);

  long live(Caller caller, Validated proof);

  Caller source(Caller caller, String id, boolean admission);

  io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.SourceFact sourceFacts(
      Caller caller, String id, boolean admission);

  boolean cleanupSafe(Caller caller);
}
