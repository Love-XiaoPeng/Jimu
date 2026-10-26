package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.McpPersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Binding;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.Map;

/** 原调用者的配置及绑定兼容门面，不持有SQL或业务规则。 */
final class JdbcMcpRecords {
  private final McpPersistenceService service;
  private final MapperMcpRecords records;

  JdbcMcpRecords(McpPersistenceService service, MapperMcpRecords records) {
    this.service = service;
    this.records = records;
  }

  void configure(McpSettings settings) {
    records.configure(settings);
  }

  McpSettings configuration() {
    return records.configuration();
  }

  boolean enabled() {
    return records.enabled();
  }

  Binding bind(String tenant, String actor) {
    return service.bind(tenant, actor);
  }

  Binding binding(Map<String, Object> task) {
    return service.binding(records.bind(task));
  }

  static TaskFailure unauthorized() {
    return new TaskFailure(403, "MCP_SERVER_NOT_AUTHORIZED");
  }

  static TaskFailure corrupt() {
    return new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }
}
