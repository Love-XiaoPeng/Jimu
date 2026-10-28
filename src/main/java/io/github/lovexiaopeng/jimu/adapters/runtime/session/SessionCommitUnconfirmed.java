package io.github.lovexiaopeng.jimu.adapters.runtime.session;

/** Leave the original slot for lease recovery; do not turn an uncertain commit into a failure. */
public final class SessionCommitUnconfirmed extends RuntimeException {
  private final SessionTransactionOutcome outcome;

  public SessionCommitUnconfirmed(SessionTransactionUnconfirmed cause) {
    super("Session slot completion unconfirmed", cause);
    outcome = cause.outcome();
  }

  public SessionTransactionOutcome outcome() {
    return outcome;
  }
}
