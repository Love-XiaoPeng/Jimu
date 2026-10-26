package io.github.lovexiaopeng.jimu.kernel.model.api;

/** 既有普通模型响应与工具槽政策；独立于会话响应 v1 的 4 槽/16KiB。 */
public final class ModelResponseLimitsV1 {
  public static final int RESPONSE_BYTES = 32768;
  public static final int TOOL_CALLS = 8;
  public static final int TOOL_INPUT_BYTES = 65535;

  private ModelResponseLimitsV1() {}
}
