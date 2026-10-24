package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;
import java.util.function.Supplier;

/** 原scope事务、锁内owner事实与具名写入；不跨请求保存授权或任务行。 */
public interface SessionScopeRevocations {
  <T> T required(long deadlineNanos, Supplier<T> work);

  void lockBudget(String tenant);

  void lockScope(String tenant, String caller);

  List<Owner> owners(Identity identity, String caller);

  void requireIdentity(Identity identity);

  interface Owner {
    boolean scopeTask();

    String state();

    void invalidateAfterTransaction();

    void revoke();

    void cancel(Identity identity);

    void account();
  }
}
