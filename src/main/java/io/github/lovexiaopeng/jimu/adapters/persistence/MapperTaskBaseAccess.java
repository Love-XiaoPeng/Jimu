package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskBaseAccess;
import java.util.List;
import java.util.Map;

/** 只在原调用点读取行值或调用既有资格；不拥有分支、SQL、事务或锁缓存。 */
final class MapperTaskBaseAccess implements TaskBaseAccess {
  private final JdbcLedger ledger;
  private final Map<String, Object> row;
  private final Runnable session;
  private final Runnable directory;
  private final Runnable todo;
  private final Runnable question;

  MapperTaskBaseAccess(
      JdbcLedger ledger,
      Map<String, Object> row,
      Runnable session,
      Runnable directory,
      Runnable todo,
      Runnable question) {
    this.ledger = ledger;
    this.row = row;
    this.session = session;
    this.directory = directory;
    this.todo = todo;
    this.question = question;
  }

  public String tenant() {
    return JdbcLedger.s(row, "tenant_id");
  }

  public String owner() {
    return JdbcLedger.s(row, "owner_actor_id");
  }

  public List<String> capabilities() {
    return ledger.caps(row);
  }

  public long epoch(Identity identity, List<String> capabilities) {
    return ledger.epoch(identity, capabilities);
  }

  public boolean sessionTask() {
    return JdbcSessionRecords.task(row);
  }

  public void sessionAccess() {
    session.run();
  }

  public boolean directoryTask() {
    return ledger.directoryTask(row);
  }

  public void directoryAccess() {
    directory.run();
  }

  public boolean todoTask() {
    return ledger.todoTask(row);
  }

  public void todoAccess() {
    todo.run();
  }

  public boolean questionTask() {
    return ledger.questionTask(row);
  }

  public void questionAccess() {
    question.run();
  }

  public long installationEpoch() {
    return JdbcLedger.n(row, "installation_epoch");
  }

  public boolean hasMcpSnapshot() {
    return row.get("mcp_snapshot") != null;
  }

  public boolean hasMcpDigest() {
    return row.get("mcp_digest") != null;
  }

  public void mcpBinding() {
    ledger.mcpService.binding(ledger.mcpRecords.bind(row));
  }
}
