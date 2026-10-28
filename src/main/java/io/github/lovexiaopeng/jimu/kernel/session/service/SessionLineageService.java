package io.github.lovexiaopeng.jimu.kernel.session.service;

/** 创建在受理原事务中执行，查询在已经核当前whole-scope的有界事务中执行。 */
public interface SessionLineageService {
  record Observation(String output, String scanDigest, int nodes, int edges) {}

  Observation observe(String tenant, String caller, String target);

  String traceSlot(
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease,
      String step,
      io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call call,
      String version,
      String digest);

  void create(String tenant, String caller, String parent);

  String trace(String tenant, String caller, String target);

  void verify(String tenant, String caller);
}
