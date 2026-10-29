package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs;
import java.util.Map;

/** 复用原参数化锁Mapper、数据库时间和query包络；不增加读取或事务。 */
final class MapperSessionHistoryProofs
    implements SessionHistoryProofs.History, SessionHistoryProofs.Step {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, step;
  private Map<String, Object> history;
  private SessionHistoryProofs.HistoryRow checkedRow;
  private DurableAgent.Checkpoint checkpoint;
  private final boolean acceptedEvent;

  MapperSessionHistoryProofs(
      JdbcSessionRecords records, Map<String, Object> task, Map<String, Object> step) {
    this(records, task, step, false);
  }

  MapperSessionHistoryProofs(
      JdbcSessionRecords records,
      Map<String, Object> task,
      Map<String, Object> step,
      boolean acceptedEvent) {
    this.records = records;
    this.task = task;
    this.step = step;
    this.acceptedEvent = acceptedEvent;
  }

  private static Long number(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : Long.valueOf(n(row, key));
  }

  public SessionHistoryProofs.HistoryRow load() {
    var rows =
        acceptedEvent
            ? records.mapperRows("SessionHistoryRetentionMapper.eventHistory", task)
            : records.historyRows(task);
    if (rows.isEmpty()) return null;
    history = rows.getFirst();
    checkedRow =
        new SessionHistoryProofs.HistoryRow(
            number(history, "record_version"),
            number(history, "history_version"),
            s(history, "binding_snapshot"),
            s(history, "binding_digest"),
            s(history, "content_state"),
            number(history, "retained"));
    return checkedRow;
  }

  SessionHistoryProofs.HistoryRow checkedRow() {
    return checkedRow;
  }

  Map<String, Object> historyRow() {
    return history;
  }

  public long version() {
    return records.version(task);
  }

  public boolean matches(String expected, String actual) {
    var query = records.appendQuery();
    if (query == null || !query.lineage()) return expected.equals(actual);
    return query.primitive("history.compare", expected.length(), () -> expected.equals(actual));
  }

  public String binding() {
    return records.contract(task).binding();
  }

  public String hash(String raw) {
    return records.policyHash(task, raw);
  }

  public SessionHistoryProofs.StepRow step() {
    return new SessionHistoryProofs.StepRow(
        number(step, "record_version"),
        s(step, "task_id"),
        number(step, "session_upper_seq"),
        s(step, "request_snapshot"),
        s(step, "request_digest"),
        s(step, "session_context_digest"),
        s(step, "session_binding_digest"));
  }

  public String task() {
    return s(task, "id");
  }

  public int checkpointVersion(String raw) {
    checkpoint = Payloads.read(raw, DurableAgent.Checkpoint.class);
    return checkpoint.version();
  }

  public boolean messagesEmpty() {
    return checkpoint.messages().isEmpty();
  }

  public String lastContent() {
    return checkpoint.messages().getLast().content();
  }

  public void validateContext(String raw) {
    records.contract(task).context(raw);
  }

  public SessionHistoryProofs.Context context(String raw) {
    var value = records.contracts.json(raw, 4096);
    return new SessionHistoryProofs.Context(
        value.path("session_id").asText(), value.path("upper_seq").asLong());
  }

  public String lastRole() {
    return checkpoint.messages().getLast().role();
  }

  public String historyBinding() {
    return s(records.history(task, true), "binding_digest");
  }
}
