package io.github.lovexiaopeng.jimu.kernel.transaction.port;

import java.util.function.Supplier;

/** 同库短事务；异常回滚，需提交后拒绝的用例先返回事实结果，再由调用者抛错。 */
public interface LedgerUnitOfWork {
  <T> T required(Supplier<T> work);
}
