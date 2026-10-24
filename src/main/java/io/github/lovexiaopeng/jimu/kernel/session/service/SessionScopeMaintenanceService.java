package io.github.lovexiaopeng.jimu.kernel.session.service;

/** 有界scope当前资格维护及拒绝事务后的持久失效记录。 */
public interface SessionScopeMaintenanceService {
  void validate();

  void persistInvalidation(String tenant, String caller);
}
