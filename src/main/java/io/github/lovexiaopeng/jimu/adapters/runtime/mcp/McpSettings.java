package io.github.lovexiaopeng.jimu.adapters.runtime.mcp;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Profile;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import org.springframework.core.env.Environment;

/**
 * Secrets are resolved only by a host-owned property reference, never serialized into snapshots.
 */
public final class McpSettings {
  private final Environment env;

  public McpSettings(Environment env) {
    this.env = env;
  }

  public boolean enabled() {
    return env.getProperty("jimu.mcp.enabled", Boolean.class, false);
  }

  public String hostId() {
    return env.getRequiredProperty("jimu.host-id");
  }

  public String credential(Profile p) {
    String value =
        env.getProperty(
            "jimu.mcp.credentials."
                + p.tenant()
                + "."
                + p.server()
                + "."
                + p.credentialRef()
                + "."
                + p.credentialVersion());
    if (value == null
        || value.isBlank()
        || value.length() > 4096
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) throw new TaskFailure(503, "MCP_UNAVAILABLE");
    return value;
  }
}
