package io.github.lovexiaopeng.jimu.kernel.mcp.api;

/** MCP 单次请求审计结果；与 invocation 的成功失败分别记录。 */
public final class McpAuditPhase {
  public static final String RESPONDED = "RESPONDED";
  public static final String FAILED = "FAILED";

  private McpAuditPhase() {}
}
