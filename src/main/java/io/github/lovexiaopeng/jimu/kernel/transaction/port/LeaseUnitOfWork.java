package io.github.lovexiaopeng.jimu.kernel.transaction.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.function.Supplier;

/** 保留租约准入的材料拒绝提交及目录连接设置；实现复用唯一Spring事务。 */
public interface LeaseUnitOfWork {
  <T> T admission(Lease lease, Supplier<T> work);

  <T> T directory(Lease lease, Supplier<T> work);
}
