package io.github.lovexiaopeng.jimu.kernel.shared.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class CommonErrorCode {
  public static final String ACCESS_DENIED = "ACCESS_DENIED";
  public static final String ACCESS_REVOKED = "ACCESS_REVOKED";
  public static final String CAPABILITY_REVOKED = "CAPABILITY_REVOKED";
  public static final String FENCE_REJECTED = "FENCE_REJECTED";
  public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE";
  public static final String RESOURCE_NOT_AUTHORIZED = "RESOURCE_NOT_AUTHORIZED";
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

  public static final String LEDGER_UNAVAILABLE = "LEDGER_UNAVAILABLE";

  public static final String ACTOR_REVOKED = "ACTOR_REVOKED";

  private CommonErrorCode() {}
}
