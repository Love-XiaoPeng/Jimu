package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.util.List;
import java.util.Map;

/** Independent v1 MCP binding; old tool/model/checkpoint wire formats remain unchanged. */
public interface McpLedger {
  List<String> NAMES =
      List.of(
          ToolCapability.LIST_MCP_RESOURCE_TEMPLATES,
          ToolCapability.LIST_MCP_RESOURCES,
          ToolCapability.READ_MCP_RESOURCE);

  record ServerInfo(String name, String version) {}

  record Profile(
      int version,
      String tenant,
      String server,
      long epoch,
      String hostId,
      String endpoint,
      String credentialRef,
      long credentialVersion,
      List<String> allowedActors,
      List<String> allowedUris,
      List<String> allowedUriPrefixes,
      List<String> allowedTemplates,
      String protocolVersion,
      ServerInfo serverInfo,
      Map<String, Object> serverCapabilities,
      String schemaDigest) {}

  record Server(Profile profile, String digest) {}

  record Binding(int version, Map<String, Server> servers) {}

  record Query(
      String method,
      String server,
      String serverSnapshotDigest,
      String schemaDigest,
      String pluginVersion,
      String pluginDigest,
      long installationEpoch) {}

  record ListSnapshot(
      int version,
      String tenant,
      String owner,
      String taskId,
      String rootInvocation,
      String server,
      String method,
      String bindingDigest,
      String queryDigest,
      String projectionDigest,
      List<String> pages,
      Map<String, Integer> cursorPositions) {}

  Binding mcpBinding(ExecutionLedger.Lease lease);

  Server mcpServer(ExecutionLedger.Lease lease, String server);

  int mcpDispatch(
      ExecutionLedger.Lease lease,
      String invocation,
      String method,
      String requestId,
      String digest,
      boolean cleanup);

  void mcpResponded(ExecutionLedger.Lease lease, String invocation, int ordinal, String error);

  void reserveMcpList(ExecutionLedger.Lease lease, String invocation);

  String mcpPage(ExecutionLedger.Lease lease, String query, String cursor);

  void mcpSucceeded(
      ExecutionLedger.Lease lease, String invocation, String output, ListSnapshot snapshot);
}
