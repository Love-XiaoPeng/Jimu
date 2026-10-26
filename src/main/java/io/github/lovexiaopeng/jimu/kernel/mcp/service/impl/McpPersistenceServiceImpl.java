package io.github.lovexiaopeng.jimu.kernel.mcp.service.impl;

import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.mcp.api.McpAuditPhase;
import io.github.lovexiaopeng.jimu.kernel.mcp.api.McpResourcePolicyV1;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.mcp.service.McpPersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Binding;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Query;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Server;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.McpErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/** 保留MCP原资格、首错、计数、快照校验和事务编排。 */
public final class McpPersistenceServiceImpl implements McpPersistenceService {
  private final McpRecords records;

  public McpPersistenceServiceImpl(McpRecords records) {
    this.records = records;
  }

  private static TaskFailure unauthorized() {
    return new TaskFailure(403, McpErrorCode.MCP_SERVER_NOT_AUTHORIZED);
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
  }

  @Override
  public Binding mcpBinding(Lease lease) {
    return records.leaseTransaction(lease, () -> binding(records.fenced(lease, true)));
  }

  @Override
  public Server mcpServer(Lease lease, String server) {
    var value = records.publicBinding(lease).servers().get(server);
    if (value == null) throw unauthorized();
    return value;
  }

  @Override
  public int mcpDispatch(
      Lease lease,
      String invocation,
      String method,
      String requestId,
      String digest,
      boolean cleanup) {
    return records.leaseTransaction(
        lease,
        () -> {
          records.fenced(lease, !cleanup);
          var call = records.selectMcpInvocation(lease.tenant(), lease.taskId(), invocation);
          if (!McpLedger.NAMES.contains(call.tool())
              || !call.state().equals(InvocationState.RUNNING))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          String kind = cleanup ? "CLEANUP" : "NORMAL";
          int count = records.countMcpIo(lease.tenant(), lease.taskId(), kind);
          if (count
              >= (cleanup
                  ? McpResourcePolicyV1.CLEANUP_TASK_SNAPSHOTS
                  : McpResourcePolicyV1.TASK_SNAPSHOTS))
            throw new TaskFailure(429, McpErrorCode.MCP_LIMIT_EXCEEDED);
          if (cleanup && records.countMcpCleanup(lease.tenant(), lease.taskId(), invocation) > 0)
            throw new TaskFailure(429, McpErrorCode.MCP_LIMIT_EXCEEDED);
          int ordinal = records.nextMcpOrdinal(lease.tenant(), lease.taskId(), invocation);
          records.insertMcpIo(
              lease.tenant(), lease.taskId(), invocation, ordinal, requestId, method, kind, digest);
          return ordinal;
        });
  }

  @Override
  public void mcpResponded(Lease lease, String invocation, int ordinal, String error) {
    records.transaction(
        () -> {
          records.fenced(lease, false);
          if (records.respondMcpIo(
                  error == null ? McpAuditPhase.RESPONDED : McpAuditPhase.FAILED,
                  error,
                  lease.tenant(),
                  lease.taskId(),
                  invocation,
                  ordinal)
              != 1) throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          return null;
        });
  }

  @Override
  public void reserveMcpList(Lease lease, String invocation) {
    records.leaseTransaction(
        lease,
        () -> {
          var budget = records.budget(lease.tenant());
          records.fenced(lease, true);
          var call = records.selectMcpListInvocation(lease.tenant(), lease.taskId(), invocation);
          if (!List.of("list_mcp_resources", "list_mcp_resource_templates").contains(call.tool())
              || !call.state().equals(InvocationState.RUNNING))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          if (records.countMcpReservations(lease.tenant(), lease.taskId()) >= 4
              || McpResourcePolicyV1.SNAPSHOT_BYTES
                  > budget.storageLimit() - (budget.storageHeld() + budget.mediaCheckpointHeld()))
            throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
          records.reserveStorage(
              lease.tenant(),
              lease.taskId(),
              "mcp:" + invocation,
              invocation,
              ReservationCategory.MCP_RESULT,
              McpResourcePolicyV1.SNAPSHOT_BYTES,
              ReservationState.RESERVED,
              UsageQuality.KNOWN);
          records.holdMcpStorage(lease.tenant());
          return null;
        });
  }

  @Override
  public String mcpPage(Lease lease, String query, String cursor) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var found = records.selectMcpPage(lease.tenant(), lease.taskId(), records.hash(cursor));
          if (found.size() != 1) throw new TaskFailure(409, McpErrorCode.MCP_CURSOR_INVALID);
          var row = found.getFirst();
          String raw = row.body();
          if (!"RETAINED".equals(row.state())
              || !query.equals(row.queryDigest())
              || !task.mcpDigest().equals(row.bindingDigest())
              || raw == null
              || !records.hash(raw).equals(row.bodyDigest()))
            throw new TaskFailure(409, McpErrorCode.MCP_CURSOR_INVALID);
          var snapshot = records.readList(raw);
          validateMcpList(task, snapshot);
          Integer page = snapshot.cursorPositions().get(records.hash(cursor));
          if (page == null || page != row.pageIndex() || !snapshot.queryDigest().equals(query))
            throw new TaskFailure(409, McpErrorCode.MCP_CURSOR_INVALID);
          return snapshot.pages().get(page);
        });
  }

  private void validateMcpList(McpRecords.Task task, ListSnapshot snapshot) {
    try {
      records.newContracts().list(snapshot);
      if (!snapshot.tenant().equals(task.tenantId())
          || !snapshot.owner().equals(task.ownerActorId())
          || !snapshot.taskId().equals(task.id())
          || !snapshot.bindingDigest().equals(task.mcpDigest())) throw corrupt();
      String name =
          snapshot.method().equals("resources/list")
              ? "list_mcp_resources"
              : "list_mcp_resource_templates";
      var contracts = records.newContracts();
      var pin = records.tools(task).pins().get(name);
      var server = binding(task).servers().get(snapshot.server());
      if (server == null
          || pin == null
          || !snapshot
              .queryDigest()
              .equals(
                  records.hash(
                      records.json(
                          new Query(
                              snapshot.method(),
                              snapshot.server(),
                              server.digest(),
                              records.schemaDigest(),
                              pin.version(),
                              pin.digest(),
                              pin.installationEpoch()))))) throw corrupt();
      var projected = new java.util.ArrayList<Object>();
      var positions = new java.util.LinkedHashMap<String, Integer>();
      int total = -1;
      for (int index = 0; index < snapshot.pages().size(); index++) {
        String page = snapshot.pages().get(index);
        if (page.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > McpResourcePolicyV1.PAGE_BYTES) throw corrupt();
        var node = records.readNode(page);
        contracts.validatePage(name, node);
        if (!node.path("snapshotId").asText().equals(snapshot.rootInvocation())
            || !node.path("snapshotSha256").asText().equals(snapshot.projectionDigest())
            || !node.path("server").asText().equals(snapshot.server())
            || !node.path("serverSnapshotDigest").asText().equals(server.digest())
            || !node.path("schemaDigest").asText().equals(server.profile().schemaDigest())
            || !node.path("serverVersion").asText().equals(server.profile().serverInfo().version())
            || node.path("page").asInt() != index
            || total != -1 && total != node.path("total").asInt()) throw corrupt();
        total = node.path("total").asInt();
        var values =
            node.path(name.equals("list_mcp_resources") ? "resources" : "resourceTemplates");
        if (node.path("returned").asInt() != values.size()) throw corrupt();
        for (var value : values)
          projected.add(
              name.equals("list_mcp_resources")
                  ? records.readResource(value.raw())
                  : records.readTemplate(value.raw()));
        if (index + 1 < snapshot.pages().size()) {
          if (!node.path("nextCursor").isTextual()
              || positions.put(records.hash(node.path("nextCursor").asText()), index + 1) != null)
            throw corrupt();
        } else if (!node.path("nextCursor").isNull()) throw corrupt();
      }
      if (projected.size() != total
          || !records.hash(records.json(projected)).equals(snapshot.projectionDigest())
          || !positions.equals(snapshot.cursorPositions())) throw corrupt();
    } catch (java.io.IOException | IllegalArgumentException error) {
      throw corrupt();
    }
  }

  @Override
  public void mcpSucceeded(Lease lease, String invocation, String output, ListSnapshot snapshot) {
    records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var producer = records.selectMcpProducer(lease.tenant(), lease.taskId(), invocation);
          if (!McpLedger.NAMES.contains(producer.tool()))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          if (InvocationState.SUCCEEDED.equals(producer.state())) {
            if (!output.equals(producer.outputSnapshot())
                || !records.hash(output).equals(producer.outputDigest())) throw corrupt();
            return null;
          }
          if (!InvocationState.RUNNING.equals(producer.state()))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
          if (snapshot != null) {
            validateMcpList(task, snapshot);
            if (!snapshot.rootInvocation().equals(invocation)
                || !snapshot.pages().getFirst().equals(output)) throw corrupt();
            String raw = records.json(snapshot);
            long size =
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + snapshot.cursorPositions().size()
                        * (long) McpResourcePolicyV1.CURSOR_METADATA_BYTES;
            if (size > McpResourcePolicyV1.SNAPSHOT_BYTES)
              throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
            var reservation =
                records.selectMcpReservation(lease.tenant(), lease.taskId(), "mcp:" + invocation);
            if (!reservation.state().equals(ReservationState.RESERVED))
              throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
            records.insertMcpResult(
                lease.tenant(),
                lease.taskId(),
                invocation,
                snapshot.server(),
                snapshot.method(),
                snapshot.bindingDigest(),
                snapshot.projectionDigest(),
                raw,
                records.hash(raw),
                size);
            for (var cursor : snapshot.cursorPositions().entrySet())
              records.insertMcpCursor(
                  cursor.getKey(),
                  cursor.getValue(),
                  snapshot.queryDigest(),
                  lease.tenant(),
                  lease.taskId(),
                  invocation);
            records.releaseMcpStorage(reservation.amount() - size, lease.tenant());
            records.settleMcpReservation(size, lease.tenant(), "mcp:" + invocation);
          }
          records.commitTool(lease, invocation, output, true);
          return null;
        });
  }

  private McpLedger.Server server(McpRecords.ServerRow row) {
    String raw = row.profileSnapshot(), digest = row.profileDigest();
    if (raw.getBytes(StandardCharsets.UTF_8).length > McpResourcePolicyV1.PROFILE_BYTES
        || !records.hash(raw).equals(digest)) throw corrupt();
    McpLedger.Profile p;
    try {
      p = records.readProfile(raw);
      records.profile(p);
    } catch (IllegalArgumentException error) {
      throw new TaskFailure(409, McpErrorCode.MCP_SNAPSHOT_MISMATCH);
    }
    if (!p.tenant().equals(row.tenantId())
        || !p.server().equals(row.serverId())
        || p.epoch() != row.epoch()) throw corrupt();
    if (!records.json(p).equals(raw)) throw corrupt();
    if (!p.hostId().equals(records.hostId()))
      throw new TaskFailure(409, McpErrorCode.MCP_SNAPSHOT_MISMATCH);
    return new McpLedger.Server(p, digest);
  }

  public McpLedger.Binding bind(String tenant, String actor) {
    if (!records.enabled()) throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
    var servers = new LinkedHashMap<String, McpLedger.Server>();
    for (var row : records.selectEnabledMcpServers(tenant)) {
      var s = server(row);
      if (s.profile().allowedActors().contains(actor)) {
        records.credential(s.profile());
        servers.put(s.profile().server(), s);
      }
    }
    if (servers.isEmpty()) throw unauthorized();
    if (servers.size() > 4) throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
    var binding = new McpLedger.Binding(1, servers);
    records.bindingContract(binding);
    if (records.json(binding).getBytes(StandardCharsets.UTF_8).length
        > McpResourcePolicyV1.BINDING_BYTES)
      throw new TaskFailure(429, McpErrorCode.MCP_LIMIT_EXCEEDED);
    return binding;
  }

  public McpLedger.Binding binding(McpRecords.Task task) {
    if (!records.enabled()) throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
    String raw = task.mcpSnapshot();
    if (raw == null) throw new TaskFailure(503, McpErrorCode.MCP_NOT_CONFIGURED);
    if (!records.hash(raw).equals(task.digestObject())
        || raw.getBytes(StandardCharsets.UTF_8).length > McpResourcePolicyV1.BINDING_BYTES)
      throw corrupt();
    McpLedger.Binding binding;
    try {
      binding = records.readBinding(raw);
      records.bindingContract(binding);
    } catch (IllegalArgumentException error) {
      throw corrupt();
    }
    for (var entry : binding.servers().entrySet()) {
      var pinned = entry.getValue();
      if (!entry.getKey().equals(pinned.profile().server())
          || !pinned.profile().tenant().equals(task.tenantObject())
          || !records.hash(records.json(pinned.profile())).equals(pinned.digest())) throw corrupt();
      var current = records.selectMcpServer(task.tenantObject(), entry.getKey());
      if (current.isEmpty() || !Boolean.TRUE.equals(current.getFirst().enabled()))
        throw unauthorized();
      var actual = server(current.getFirst());
      if (!actual.profile().allowedActors().contains(task.ownerObject())) throw unauthorized();
      if (actual.profile().epoch() != pinned.profile().epoch()
          || !actual.digest().equals(pinned.digest()))
        throw new TaskFailure(409, McpErrorCode.MCP_SNAPSHOT_MISMATCH);
      records.credential(pinned.profile());
    }
    return binding;
  }
}
