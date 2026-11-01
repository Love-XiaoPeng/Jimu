package io.github.lovexiaopeng.jimu.adapters.runtime.session;

/** 单次真实事务的同步完成回执；缺失回执不能从异常类型推断已回滚。 */
public record SessionTransactionOutcome(
    Completion completion, Phase phase, int query, int owner, int connection, long deadlineNanos) {
  public enum Completion {
    NOT_STARTED,
    BEGIN_UNCONFIRMED,
    UNKNOWN,
    COMMITTED,
    ROLLED_BACK
  }

  public enum Phase {
    BEFORE_EXECUTE,
    BEGIN,
    CONNECTION_SETUP,
    BUSINESS,
    FINALIZE,
    BEFORE_COMMIT
  }
}
