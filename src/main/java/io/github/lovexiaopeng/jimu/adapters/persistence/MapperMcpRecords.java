package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings;
import io.github.lovexiaopeng.jimu.kernel.mcp.port.McpRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Binding;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Profile;
import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.core.env.StandardEnvironment;

/** 单项持久化/codec适配，行字段仅在Service到达原读取位置时访问。 */
final class MapperMcpRecords implements McpRecords {
  private final JdbcLedger ledger;
  private final McpContracts contracts;
  private McpSettings settings = new McpSettings(new StandardEnvironment());

  MapperMcpRecords(JdbcLedger ledger) {
    this.ledger = ledger;
    try {
      contracts = new McpContracts();
    } catch (IOException error) {
      throw new IllegalStateException("MCP contracts unavailable", error);
    }
  }

  void configure(McpSettings settings) {
    this.settings = settings;
  }

  void copyConfiguration(MapperMcpRecords original) {
    settings = original.settings;
  }

  Task bind(Map<String, Object> row) {
    return new TaskView(row);
  }

  private record TaskView(Map<String, Object> row) implements Task {
    public String tenantId() {
      return (String) row.get("tenant_id");
    }

    public String id() {
      return (String) row.get("id");
    }

    public String ownerActorId() {
      return (String) row.get("owner_actor_id");
    }

    public String mcpSnapshot() {
      return (String) row.get("mcp_snapshot");
    }

    public String mcpDigest() {
      return (String) row.get("mcp_digest");
    }

    public Object tenantObject() {
      return row.get("tenant_id");
    }

    public Object ownerObject() {
      return row.get("owner_actor_id");
    }

    public Object digestObject() {
      return row.get("mcp_digest");
    }
  }

  private record ServerRowView(Map<String, Object> row) implements ServerRow {
    public String profileSnapshot() {
      return (String) row.get("profile_snapshot");
    }

    public String profileDigest() {
      return (String) row.get("profile_digest");
    }

    public Object tenantId() {
      return row.get("tenant_id");
    }

    public Object serverId() {
      return row.get("server_id");
    }

    public long epoch() {
      return ((Number) row.get("epoch")).longValue();
    }

    public Object enabled() {
      return row.get("enabled");
    }
  }

  private record InvocationView(Map<String, Object> row) implements Invocation {
    public String tool() {
      return (String) row.get("tool");
    }

    public String state() {
      return (String) row.get("state");
    }

    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }

    public String outputDigest() {
      return (String) row.get("output_digest");
    }
  }

  private record BudgetView(Map<String, Object> row) implements Budget {
    public long storageLimit() {
      return ((Number) row.get("storage_limit")).longValue();
    }

    public long storageHeld() {
      return ((Number) row.get("storage_held")).longValue();
    }

    public long mediaCheckpointHeld() {
      return ((Number) row.get("media_checkpoint_held")).longValue();
    }
  }

  private record ReservationView(Map<String, Object> row) implements Reservation {
    public String state() {
      return (String) row.get("state");
    }

    public long amount() {
      return ((Number) row.get("amount")).longValue();
    }
  }

  private record PageView(Map<String, Object> row) implements Page {
    public String body() {
      return (String) row.get("body");
    }

    public String state() {
      return (String) row.get("state");
    }

    public String queryDigest() {
      return (String) row.get("query_digest");
    }

    public String bindingDigest() {
      return (String) row.get("binding_digest");
    }

    public String bodyDigest() {
      return (String) row.get("body_digest");
    }

    public long pageIndex() {
      return ((Number) row.get("page_index")).longValue();
    }
  }

  private static <T> List<T> lazy(
      List<Map<String, Object>> rows, Function<Map<String, Object>, T> wrap) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public T get(int i) {
        return wrap.apply(rows.get(i));
      }
    };
  }

  private record JsonNodeView(com.fasterxml.jackson.databind.JsonNode node) implements Node {
    public Node path(String name) {
      return new JsonNodeView(node.path(name));
    }

    public String asText() {
      return node.asText();
    }

    public int asInt() {
      return node.asInt();
    }

    public int size() {
      return node.size();
    }

    public boolean isTextual() {
      return node.isTextual();
    }

    public boolean isNull() {
      return node.isNull();
    }

    public String raw() {
      return node.toString();
    }

    public java.util.Iterator<Node> iterator() {
      var it = node.iterator();
      return new java.util.Iterator<>() {
        public boolean hasNext() {
          return it.hasNext();
        }

        public Node next() {
          return new JsonNodeView(it.next());
        }
      };
    }
  }

  public <T> T leaseTransaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public <T> T transaction(Supplier<T> work) {
    return ledger.tx(work);
  }

  public Task fenced(Lease lease, boolean admission) {
    return bind(ledger.fenced(lease, admission));
  }

  public Budget budget(String tenant) {
    return new BudgetView(ledger.bucket(tenant));
  }

  public Binding publicBinding(Lease lease) {
    return ledger.mcpBinding(lease);
  }

  public ExecutionLedger.ToolSnapshot tools(Task task) {
    return ledger.tools(((TaskView) task).row());
  }

  public void reserveStorage(
      String tenant,
      String task,
      String id,
      String invocation,
      String category,
      long amount,
      String state,
      String outcome) {
    ledger.reserve(tenant, task, id, invocation, category, amount, state, outcome);
  }

  public void commitTool(Lease lease, String invocation, String output, boolean mcp) {
    ledger.commitTool(lease, invocation, output, mcp);
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public String hostId() {
    return settings.hostId();
  }

  public void credential(Profile profile) {
    settings.credential(profile);
  }

  public void profile(Profile profile) {
    contracts.profile(profile);
  }

  public void bindingContract(Binding binding) {
    contracts.binding(binding);
  }

  public Contracts newContracts() throws IOException {
    var fresh = new McpContracts();
    return new Contracts() {
      public void list(ListSnapshot value) {
        fresh.list(value);
      }

      public void validatePage(String name, Node node) {
        fresh.contract(name).out().validate(((JsonNodeView) node).node());
      }
    };
  }

  public String schemaDigest() {
    return McpContracts.DIGEST;
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public Profile readProfile(String raw) {
    return Payloads.read(raw, Profile.class);
  }

  public Binding readBinding(String raw) {
    return Payloads.read(raw, Binding.class);
  }

  public ListSnapshot readList(String raw) {
    return Payloads.read(raw, ListSnapshot.class);
  }

  public Node readNode(String raw) {
    return new JsonNodeView(Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
  }

  public Object readResource(String raw) {
    return Payloads.read(
        raw, io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort.Resource.class);
  }

  public Object readTemplate(String raw) {
    return Payloads.read(
        raw, io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort.Template.class);
  }

  public Invocation selectMcpInvocation(String tenant, String task, String invocation) {
    return new InvocationView(
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectMcpInvocation(ledger.queryContext(), tenant, task, invocation)));
  }

  public Integer countMcpIo(String tenant, String task, String kind) {
    return org.springframework.dao.support.DataAccessUtils.nullableSingleResult(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpIoMapper.class)
            .countMcpIo(ledger.queryContext(), tenant, task, kind));
  }

  public Integer countMcpCleanup(String tenant, String task, String invocation) {
    return org.springframework.dao.support.DataAccessUtils.nullableSingleResult(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpIoMapper.class)
            .countMcpCleanup(ledger.queryContext(), tenant, task, invocation));
  }

  public Integer nextMcpOrdinal(String tenant, String task, String invocation) {
    return org.springframework.dao.support.DataAccessUtils.nullableSingleResult(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpIoMapper.class)
            .nextMcpOrdinal(ledger.queryContext(), tenant, task, invocation));
  }

  public int insertMcpIo(
      String tenant,
      String task,
      String invocation,
      int ordinal,
      String requestId,
      String method,
      String kind,
      String digest) {
    return ledger
        .mappers
        .session()
        .getMapper(io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpIoMapper.class)
        .insertMcpIo(
            ledger.queryContext(),
            tenant,
            task,
            invocation,
            ordinal,
            requestId,
            method,
            kind,
            digest);
  }

  public int respondMcpIo(
      String phase, String error, String tenant, String task, String invocation, int ordinal) {
    return ledger
        .mappers
        .session()
        .getMapper(io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpIoMapper.class)
        .respondMcpIo(ledger.queryContext(), phase, error, tenant, task, invocation, ordinal);
  }

  public Invocation selectMcpListInvocation(String tenant, String task, String invocation) {
    return new InvocationView(
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectMcpListInvocation(ledger.queryContext(), tenant, task, invocation)));
  }

  public Integer countMcpReservations(String tenant, String task) {
    return org.springframework.dao.support.DataAccessUtils.nullableSingleResult(
        ledger.mappers.reservations().countMcpReservations(ledger.queryContext(), tenant, task));
  }

  public int holdMcpStorage(String tenant) {
    return ledger.mappers.budgets().holdMcpStorage(ledger.queryContext(), tenant);
  }

  public List<Page> selectMcpPage(String tenant, String task, String token) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpCursorMapper.class)
            .selectMcpPage(ledger.queryContext(), tenant, task, token),
        PageView::new);
  }

  public Invocation selectMcpProducer(String tenant, String task, String invocation) {
    return new InvocationView(
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectMcpProducer(ledger.queryContext(), tenant, task, invocation)));
  }

  public Reservation selectMcpReservation(String tenant, String task, String reservation) {
    return new ReservationView(
        JdbcLedger.first(
            ledger
                .mappers
                .reservations()
                .selectMcpReservation(ledger.queryContext(), tenant, task, reservation)));
  }

  public int insertMcpResult(
      String tenant,
      String task,
      String invocation,
      String server,
      String method,
      String binding,
      String projection,
      String body,
      String digest,
      long size) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpResultMapper.class)
        .insertMcpResult(
            ledger.queryContext(),
            tenant,
            task,
            invocation,
            server,
            method,
            binding,
            projection,
            body,
            digest,
            size);
  }

  public int insertMcpCursor(
      String token,
      Integer page,
      String queryDigest,
      String tenant,
      String task,
      String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpCursorMapper.class)
        .insertMcpCursor(ledger.queryContext(), token, page, queryDigest, tenant, task, invocation);
  }

  public int releaseMcpStorage(long bytes, String tenant) {
    return ledger.mappers.budgets().releaseMcpStorage(ledger.queryContext(), bytes, tenant);
  }

  public int settleMcpReservation(long size, String tenant, String reservation) {
    return ledger
        .mappers
        .reservations()
        .settleMcpReservation(ledger.queryContext(), size, tenant, reservation);
  }

  public List<ServerRow> selectEnabledMcpServers(String tenant) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpServerMapper.class)
            .selectEnabledMcpServers(ledger.queryContext(), tenant),
        ServerRowView::new);
  }

  public List<ServerRow> selectMcpServer(Object tenant, String server) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpServerMapper.class)
            .selectMcpServer(ledger.queryContext(), tenant, server),
        ServerRowView::new);
  }
}
