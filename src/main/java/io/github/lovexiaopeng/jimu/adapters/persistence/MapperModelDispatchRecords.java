package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.model.port.ModelDispatchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** 模型失败收据的实际 Mapper 适配，沿 evaluation→task 原锁序加入调用者事务。 */
final class MapperModelDispatchRecords implements ModelDispatchRecords {
  private final JdbcLedger ledger;

  MapperModelDispatchRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public Dispatch lockSettlement(Lease lease, String dispatch) {

    var evaluation = ledger.evaluation(lease, false);
    var task = ledger.fenced(lease, false);
    // 已发生usage的结算只核原dispatch/attempt/lease/fence，不重新授予读取scope。
    new MapperCommitFence(ledger, lease).settlement(dispatch);

    return new ModelDispatchRecords.Dispatch() {
      @Override
      public io.github.lovexiaopeng.jimu.kernel.model.port.ModelCostRecords costs() {
        return new MapperModelCosts(ledger, lease, task, evaluation, dispatch);
      }

      @Override
      public boolean session() {
        return JdbcSessionRecords.task(task);
      }

      @Override
      public void markFailed(String code) {
        ledger
            .mappers
            .dispatches()
            .failOriginalDispatch(
                ledger.queryContext(), code, lease.tenant(), dispatch, lease.taskId());
      }

      @Override
      public long reservedTokens() {
        return ((Number)
                JdbcLedger.first(
                        ledger
                            .mappers
                            .reservations()
                            .selectTokenAmount(ledger.queryContext(), lease.tenant(), dispatch))
                    .get("amount"))
            .longValue();
      }

      @Override
      public void settleTokens(long actual, long released) {
        ledger
            .mappers
            .budgets()
            .releaseModelTokens(ledger.queryContext(), released, lease.tenant());
        ledger
            .mappers
            .tasks()
            .releaseModelTokens(ledger.queryContext(), released, lease.tenant(), lease.taskId());
        ledger
            .mappers
            .reservations()
            .settleModelTokens(ledger.queryContext(), actual, lease.tenant(), dispatch);
      }

      @Override
      public void holdUnknown() {
        ledger
            .mappers
            .reservations()
            .holdFailedModelUnknown(ledger.queryContext(), lease.tenant(), dispatch);
      }

      @Override
      public void accountSession() {
        ledger.accountSession(task);
      }
    };
  }
}
