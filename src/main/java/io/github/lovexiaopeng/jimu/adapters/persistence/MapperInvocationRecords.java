package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;

/** 原调用者事务内的调用结算；未知效果保留预留，不创建额外事务或重试。 */
final class MapperInvocationRecords implements InvocationRecords {
  private final JdbcLedger ledger;

  MapperInvocationRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public boolean protectedInvocation(Lease lease, String invocation, String tool) {
    return !ledger
        .mappers
        .invocations()
        .selectProtectedInvocation(
            ledger.queryContext(), lease.tenant(), lease.taskId(), invocation, tool)
        .isEmpty();
  }

  @Override
  public Invocation lockSettlement(Lease lease, String invocation) {
    var row = ledger.fenced(lease, false);
    return new Invocation() {
      @Override
      public boolean session() {
        return JdbcSessionRecords.task(row);
      }

      @Override
      public int failRunning() {
        return ledger
            .mappers
            .invocations()
            .failRunning(ledger.queryContext(), lease.tenant(), invocation, lease.taskId());
      }

      @Override
      public void settleUnit() {
        ledger
            .mappers
            .reservations()
            .settleToolUnit(ledger.queryContext(), lease.tenant(), invocation);
      }

      @Override
      public void failureEvent(String code) {
        ledger.event(row, "failure:" + invocation, "tool.failed", code);
      }

      @Override
      public void markUnknown() {
        ledger
            .mappers
            .invocations()
            .markInvocationUnknown(
                ledger.queryContext(), lease.tenant(), invocation, lease.taskId());
      }

      @Override
      public void holdUnknown() {
        ledger
            .mappers
            .reservations()
            .holdReservedModelUnknown(ledger.queryContext(), lease.tenant(), invocation);
      }

      @Override
      public void reconciliationRequired() {
        ledger.terminal(row, TaskState.NEEDS_RECONCILIATION, TaskErrorCode.EFFECT_OUTCOME_UNKNOWN);
      }
    };
  }
}
