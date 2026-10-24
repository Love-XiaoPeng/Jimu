package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called only inside the ledger's run/budget/task fenced transactions. */
final class JdbcMcpRecords {
  private final JdbcTemplate db;
  private final McpContracts contracts;
  private McpSettings settings = new McpSettings(new StandardEnvironment());

  JdbcMcpRecords(JdbcTemplate db) {
    this.db = db;
    try {
      contracts = new McpContracts();
    } catch (IOException error) {
      throw new IllegalStateException("MCP contracts unavailable", error);
    }
  }

  void configure(McpSettings settings) {
    this.settings = settings;
  }

  void copyConfiguration(JdbcMcpRecords original) {
    settings = original.settings;
  }

  boolean enabled() {
    return settings.enabled();
  }

  private McpLedger.Server server(Map<String, Object> row) {
    String raw = (String) row.get("profile_snapshot"), digest = (String) row.get("profile_digest");
    if (raw.getBytes(StandardCharsets.UTF_8).length > 8192 || !Payloads.hash(raw).equals(digest))
      throw corrupt();
    McpLedger.Profile p;
    try {
      p = Payloads.read(raw, McpLedger.Profile.class);
      contracts.profile(p);
    } catch (IllegalArgumentException error) {
      throw new TaskFailure(409, "MCP_SNAPSHOT_MISMATCH");
    }
    if (!p.tenant().equals(row.get("tenant_id"))
        || !p.server().equals(row.get("server_id"))
        || p.epoch() != ((Number) row.get("epoch")).longValue()) throw corrupt();
    if (!Payloads.json(p).equals(raw)) throw corrupt();
    if (!p.hostId().equals(settings.hostId())) throw new TaskFailure(409, "MCP_SNAPSHOT_MISMATCH");
    return new McpLedger.Server(p, digest);
  }

  McpLedger.Binding bind(String tenant, String actor) {
    if (!enabled()) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    var servers = new LinkedHashMap<String, McpLedger.Server>();
    for (var row :
        db.queryForList(
            "SELECT * FROM mcp_server WHERE tenant_id=? AND enabled=TRUE ORDER BY server_id FOR SHARE",
            tenant)) {
      var s = server(row);
      if (s.profile().allowedActors().contains(actor)) {
        settings.credential(s.profile());
        servers.put(s.profile().server(), s);
      }
    }
    if (servers.isEmpty()) throw unauthorized();
    if (servers.size() > 4) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    var binding = new McpLedger.Binding(1, servers);
    contracts.binding(binding);
    if (Payloads.json(binding).getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
    return binding;
  }

  McpLedger.Binding binding(Map<String, Object> task) {
    if (!enabled()) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    String raw = (String) task.get("mcp_snapshot");
    if (raw == null) throw new TaskFailure(503, "MCP_NOT_CONFIGURED");
    if (!Payloads.hash(raw).equals(task.get("mcp_digest"))
        || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw corrupt();
    McpLedger.Binding binding;
    try {
      binding = Payloads.read(raw, McpLedger.Binding.class);
      contracts.binding(binding);
    } catch (IllegalArgumentException error) {
      throw corrupt();
    }
    for (var entry : binding.servers().entrySet()) {
      var pinned = entry.getValue();
      if (!entry.getKey().equals(pinned.profile().server())
          || !pinned.profile().tenant().equals(task.get("tenant_id"))
          || !Payloads.hash(Payloads.json(pinned.profile())).equals(pinned.digest()))
        throw corrupt();
      var current =
          db.queryForList(
              "SELECT * FROM mcp_server WHERE tenant_id=? AND server_id=? FOR SHARE",
              task.get("tenant_id"),
              entry.getKey());
      if (current.isEmpty() || !Boolean.TRUE.equals(current.getFirst().get("enabled")))
        throw unauthorized();
      var actual = server(current.getFirst());
      if (!actual.profile().allowedActors().contains(task.get("owner_actor_id")))
        throw unauthorized();
      if (actual.profile().epoch() != pinned.profile().epoch()
          || !actual.digest().equals(pinned.digest()))
        throw new TaskFailure(409, "MCP_SNAPSHOT_MISMATCH");
      settings.credential(pinned.profile());
    }
    return binding;
  }

  static TaskFailure unauthorized() {
    return new TaskFailure(403, "MCP_SERVER_NOT_AUTHORIZED");
  }

  static TaskFailure corrupt() {
    return new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }
}
