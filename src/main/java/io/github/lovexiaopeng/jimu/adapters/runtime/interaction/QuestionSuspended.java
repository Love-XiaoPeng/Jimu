package io.github.lovexiaopeng.jimu.adapters.runtime.interaction;

/** Only thrown after the ledger has committed pause. Contains no user data. */
public final class QuestionSuspended extends RuntimeException {
  public QuestionSuspended() {
    super("Durable question suspension", null, false, false);
  }
}
