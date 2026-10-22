package io.github.lovexiaopeng.jimu.adapters.runtime.todo;

/** Exit this attempt without guessing whether the database committed. */
public final class TodoCommitUnconfirmed extends RuntimeException {
  public TodoCommitUnconfirmed() {
    super("Todo transaction result unconfirmed");
  }
}
