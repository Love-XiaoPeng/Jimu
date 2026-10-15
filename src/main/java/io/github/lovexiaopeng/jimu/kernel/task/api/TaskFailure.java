package io.github.lovexiaopeng.jimu.kernel.task.api;

/** Safe failure code; adapters must not return database or provider exception text. */
public final class TaskFailure extends RuntimeException {
  private final int status;
  private final String code;

  public TaskFailure(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
