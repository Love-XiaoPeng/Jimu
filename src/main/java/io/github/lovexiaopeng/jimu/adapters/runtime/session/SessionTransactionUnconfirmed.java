package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import org.springframework.transaction.TransactionSystemException;

/** execute已调用且没有确定完成回执；HTTP和worker保留同一cause及阶段。 */
public final class SessionTransactionUnconfirmed extends TransactionSystemException {
  private final SessionTransactionOutcome outcome;

  public SessionTransactionUnconfirmed(Throwable cause, SessionTransactionOutcome outcome) {
    super("Session transaction completion unconfirmed", cause);
    this.outcome = outcome;
    if (cause instanceof TransactionSystemException transaction
        && transaction.getApplicationException() != null)
      initApplicationException(transaction.getApplicationException());
  }

  public SessionTransactionOutcome outcome() {
    return outcome;
  }
}
