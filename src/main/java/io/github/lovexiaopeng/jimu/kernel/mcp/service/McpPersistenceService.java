package io.github.lovexiaopeng.jimu.kernel.mcp.service;

import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Binding;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Server;

/** MCP授权绑定、派发和列表快照的原事务用例。 */
public interface McpPersistenceService {
  Binding bind(String tenant, String actor);

  Binding binding(McpRecords.Task task);

  Binding mcpBinding(Lease lease);

  Server mcpServer(Lease lease, String server);

  int mcpDispatch(
      Lease lease,
      String invocation,
      String method,
      String requestId,
      String digest,
      boolean cleanup);

  void mcpResponded(Lease lease, String invocation, int ordinal, String error);

  void reserveMcpList(Lease lease, String invocation);

  String mcpPage(Lease lease, String query, String cursor);

  void mcpSucceeded(Lease lease, String invocation, String output, ListSnapshot snapshot);
}
