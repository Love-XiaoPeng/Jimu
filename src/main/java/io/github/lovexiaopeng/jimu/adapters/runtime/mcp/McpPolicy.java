package io.github.lovexiaopeng.jimu.adapters.runtime.mcp;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Profile;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.net.URI;
import java.util.Set;

/** Resource identifiers are only RPC data and are never dereferenced. */
public final class McpPolicy {
  private static final Set<String> FORBIDDEN =
      Set.of("http", "https", "file", "ftp", "data", "javascript", "jar", "ws", "wss");

  private McpPolicy() {}

  public static boolean safe(String uri) {
    if (uri == null
        || uri.length() > 2048
        || !uri.matches("[a-z][a-z0-9+.-]*://[A-Za-z0-9._-]+/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*"))
      return false;
    return !FORBIDDEN.contains(uri.substring(0, uri.indexOf(':')));
  }

  public static boolean allowed(Profile profile, String uri) {
    return safe(uri)
        && (profile.allowedUris().contains(uri)
            || profile.allowedUriPrefixes().stream().anyMatch(uri::startsWith));
  }

  public static void require(Profile profile, String uri) {
    if (!allowed(profile, uri)) throw new TaskFailure(403, "MCP_URI_NOT_AUTHORIZED");
  }

  public static void profile(Profile p) {
    if (!p.endpoint().matches("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/mcp")
        || URI.create(p.endpoint()).getPort() > 65535)
      throw new IllegalArgumentException("Unsupported MCP endpoint");
    for (String uri : p.allowedUris())
      if (!safe(uri)) throw new IllegalArgumentException("Unsafe MCP URI");
    for (String prefix : p.allowedUriPrefixes())
      if (!prefix.endsWith("/") || !safe(prefix + "probe"))
        throw new IllegalArgumentException("Unsafe MCP prefix");
    for (String template : p.allowedTemplates()) {
      if (!template.matches("[^{}]+\\{[A-Za-z][A-Za-z0-9_]*}[^{}]*")
          || !allowed(p, template.replaceFirst("\\{[A-Za-z][A-Za-z0-9_]*}", "probe")))
        throw new IllegalArgumentException("Unsafe MCP template");
    }
  }
}
