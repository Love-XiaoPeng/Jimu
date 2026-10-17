package io.github.lovexiaopeng.jimu.plugin.api;

/** Stable workspace boundary error; contains no host path or file contents. */
public final class FileFailure extends RuntimeException {
  private final String code;

  public FileFailure(String code) {
    super(code);
    this.code = code;
  }

  public FileFailure(String code, Throwable cause) {
    super(code, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
