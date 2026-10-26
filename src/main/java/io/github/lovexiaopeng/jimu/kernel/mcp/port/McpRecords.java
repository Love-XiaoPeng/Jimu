package io.github.lovexiaopeng.jimu.kernel.mcp.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Binding;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.ListSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.Profile;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/** MCP原始行的惰性视图；单项数据库/编解码操作，不持有业务资格。 */
public interface McpRecords {
  interface Task {
    String tenantId();

    String id();

    String ownerActorId();

    String mcpSnapshot();

    String mcpDigest();

    Object tenantObject();

    Object ownerObject();

    Object digestObject();
  }

  interface ServerRow {
    String profileSnapshot();

    String profileDigest();

    Object tenantId();

    Object serverId();

    long epoch();

    Object enabled();
  }

  interface Invocation {
    String tool();

    String state();

    String outputSnapshot();

    String outputDigest();
  }

  interface Budget {
    long storageLimit();

    long storageHeld();

    long mediaCheckpointHeld();
  }

  interface Reservation {
    String state();

    long amount();
  }

  interface Page {
    String body();

    String state();

    String queryDigest();

    String bindingDigest();

    String bodyDigest();

    long pageIndex();
  }

  interface Node extends Iterable<Node> {
    Node path(String name);

    String asText();

    int asInt();

    int size();

    boolean isTextual();

    boolean isNull();

    String raw();
  }

  interface Contracts {
    void list(ListSnapshot value);

    void validatePage(String name, Node node);
  }

  <T> T leaseTransaction(Lease lease, Supplier<T> work);

  <T> T transaction(Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  Budget budget(String tenant);

  Binding publicBinding(Lease lease);

  ExecutionLedger.ToolSnapshot tools(Task task);

  void reserveStorage(
      String tenant,
      String task,
      String id,
      String invocation,
      String category,
      long amount,
      String state,
      String outcome);

  void commitTool(Lease lease, String invocation, String output, boolean mcp);

  boolean enabled();

  String hostId();

  void credential(Profile profile);

  void profile(Profile profile);

  void bindingContract(Binding binding);

  Contracts newContracts() throws IOException;

  String schemaDigest();

  String json(Object value);

  String hash(String value);

  Profile readProfile(String raw);

  Binding readBinding(String raw);

  ListSnapshot readList(String raw);

  Node readNode(String raw);

  Object readResource(String raw);

  Object readTemplate(String raw);

  Invocation selectMcpInvocation(String tenant, String task, String invocation);

  Integer countMcpIo(String tenant, String task, String kind);

  Integer countMcpCleanup(String tenant, String task, String invocation);

  Integer nextMcpOrdinal(String tenant, String task, String invocation);

  int insertMcpIo(
      String tenant,
      String task,
      String invocation,
      int ordinal,
      String requestId,
      String method,
      String kind,
      String digest);

  int respondMcpIo(
      String phase, String error, String tenant, String task, String invocation, int ordinal);

  Invocation selectMcpListInvocation(String tenant, String task, String invocation);

  Integer countMcpReservations(String tenant, String task);

  int holdMcpStorage(String tenant);

  List<Page> selectMcpPage(String tenant, String task, String token);

  Invocation selectMcpProducer(String tenant, String task, String invocation);

  Reservation selectMcpReservation(String tenant, String task, String reservation);

  int insertMcpResult(
      String tenant,
      String task,
      String invocation,
      String server,
      String method,
      String binding,
      String projection,
      String body,
      String digest,
      long size);

  int insertMcpCursor(
      String token,
      Integer page,
      String queryDigest,
      String tenant,
      String task,
      String invocation);

  int releaseMcpStorage(long bytes, String tenant);

  int settleMcpReservation(long size, String tenant, String reservation);

  List<ServerRow> selectEnabledMcpServers(String tenant);

  List<ServerRow> selectMcpServer(Object tenant, String server);
}
