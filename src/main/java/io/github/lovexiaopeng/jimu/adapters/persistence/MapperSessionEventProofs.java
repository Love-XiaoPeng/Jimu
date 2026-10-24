package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.NativeReadReceiptMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryAttemptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReceiptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventProofs;
import java.time.LocalDateTime;
import java.util.Map;

/** 每次证明独立上下文；MP查询使用原DS/query/结果计量，不缓存事实。 */
final class MapperSessionEventProofs implements SessionEventProofs.Proof {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, row;
  private Map<String, Object> invocation, read;
  private JsonNode value;

  MapperSessionEventProofs(
      JdbcSessionRecords records, Map<String, Object> task, Map<String, Object> row) {
    this.records = records;
    this.task = task;
    this.row = row;
  }

  JsonNode value() {
    return value;
  }

  public SessionEventProofs.Subject subject() {
    return new SessionEventProofs.Subject(
        s(task, "tenant_id"),
        s(task, "id"),
        records.version(task),
        JdbcSessionScopes.task(task),
        JdbcSessionRecords.traceTask(task),
        s(task, "request_digest"));
  }

  public SessionEventProofs.Stored stored() {
    return new SessionEventProofs.Stored(
        s(row, "tenant_id"),
        s(row, "task_id"),
        n(row, "record_version"),
        n(row, "event_version"),
        s(row, "content_state"),
        s(row, "event_snapshot"),
        n(row, "event_bytes"),
        s(row, "event_digest"),
        n(row, "seq"),
        s(row, "type"),
        (LocalDateTime) row.get("occurred_at"),
        s(row, "source_step_id"),
        s(row, "source_invocation_id"),
        s(row, "fact_key"),
        s(row, "source_digest"),
        n(row, "reliable_event_seq"),
        s(row, "committed_attempt_id"),
        row.get("committed_fence") == null ? null : Long.valueOf(n(row, "committed_fence")));
  }

  public void operation() {
    records.proofOperation();
  }

  private static String nullable(JsonNode value) {
    return value.isNull() ? null : value.asText();
  }

  public SessionEventProofs.Document decode() {
    value = records.contracts.json(s(row, "event_snapshot"), 16384);
    var source = value.path("source");
    return new SessionEventProofs.Document(
        value.path("version").isIntegralNumber(),
        value.path("version").asLong(),
        value.path("type").asText(),
        value.path("session_id").asText(),
        value.path("seq").asLong(),
        value.path("time").asText(),
        nullable(source.path("step_id")),
        nullable(source.path("invocation_id")),
        source.path("kind").asText(),
        value.path("data").path("ordinal").asLong(),
        value.path("data").path("state").asText(),
        nullable(value.path("data").path("code")),
        Payloads.json(value.path("data")),
        value.path("source_event_seqs").isEmpty(),
        value.path("source_event_refs").isEmpty());
  }

  public void validateDocument() {
    records.contract(task).event(s(row, "event_snapshot"));
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public boolean sourceMatches(String kind) {
    return value
        .path("source")
        .equals(Payloads.read(Payloads.json(records.source(kind, null, null)), JsonNode.class));
  }

  private QueryWrapper<Object> base() {
    return new QueryWrapper<>().eq("tenant_id", s(task, "tenant_id")).eq("task_id", s(task, "id"));
  }

  private QueryWrapper<Object> boundedStep(QueryWrapper<Object> wrapper) {
    return wrapper.apply(
        "(NOT {0} OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
        records.priorProof());
  }

  public SessionEventProofs.Step step() {
    var st =
        records.proofRow(
            ScopeQueryStepReadMapper.class,
            boundedStep(base().eq("id", s(row, "source_step_id")))
                .select(
                    "id",
                    "ordinal",
                    "request_snapshot",
                    "request_digest",
                    "response_snapshot",
                    "response_digest"));
    return new SessionEventProofs.Step(
        s(st, "id"),
        n(st, "ordinal"),
        s(st, "request_snapshot"),
        s(st, "request_digest"),
        s(st, "response_snapshot"),
        s(st, "response_digest"));
  }

  public SessionEventProofs.Invocation invocation() {
    invocation =
        records.proofRow(
            HistoryInvocationReadMapper.class,
            base()
                .eq("id", s(row, "source_invocation_id"))
                .eq("step_id", s(row, "source_step_id"))
                .apply(
                    "(NOT {0} OR (OCTET_LENGTH(input_snapshot)<=4096 AND COALESCE(OCTET_LENGTH(output_snapshot),0)<=32768))",
                    records.priorProof())
                .select(
                    "id",
                    "step_id",
                    "state",
                    "input_snapshot",
                    "input_digest",
                    "output_snapshot",
                    "output_digest",
                    "slot",
                    "provider_id",
                    "tool",
                    "plugin_version",
                    "plugin_digest",
                    "installation_epoch"));
    return new SessionEventProofs.Invocation(
        s(invocation, "id"),
        s(invocation, "state"),
        s(invocation, "input_digest"),
        s(invocation, "output_snapshot"),
        s(invocation, "output_digest"));
  }

  public SessionEventProofs.Read read() {
    read =
        records.proofRow(
            NativeReadReceiptMapper.class,
            base()
                .eq("invocation_id", s(invocation, "id"))
                .eq("step_id", s(invocation, "step_id"))
                .select(
                    "receipt_seq",
                    "outcome",
                    "output_digest",
                    "output_bytes",
                    "target_seq",
                    "before_count",
                    "after_count",
                    "upper_seq",
                    "failure_code",
                    "binding_digest",
                    "target_digest",
                    "input_digest",
                    "window_start",
                    "window_end"));
    return new SessionEventProofs.Read(
        nullableNumber(read, "receipt_seq"),
        s(read, "outcome"),
        s(read, "output_digest"),
        nullableNumber(read, "output_bytes"));
  }

  private static Long nullableNumber(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : Long.valueOf(n(row, key));
  }

  public String readReceipt() {
    return Payloads.json(records.receipt(read));
  }

  public String scopeRead() {
    return records.verifyScopeRead(task, row, value);
  }

  public void readRelation() {
    records.verifyReadRelation(task, row, value, invocation, read);
  }

  public SessionEventProofs.Terminal terminal() {
    var terminal =
        records.proofRow(
            ScopeQueryReceiptReadMapper.class,
            base().eq("fact_id", "terminal").select("event_id", "type", "status", "code"));
    var frozen = new java.util.LinkedHashMap<String, Object>();
    for (String column : java.util.List.of("event_id", "type", "status", "code"))
      frozen.put(column, terminal.get(column));
    return new SessionEventProofs.Terminal(
        s(terminal, "status"), s(terminal, "code"), Payloads.hash(Payloads.json(frozen)));
  }

  public String reliableType(long seq) {
    return s(
        records.proofRow(ScopeQueryReceiptReadMapper.class, base().eq("seq", seq).select("type")),
        "type");
  }

  public void requireAttempt(String attempt, long fence) {
    records.proofRow(
        ScopeQueryAttemptReadMapper.class,
        base().eq("id", attempt).eq("fence", fence).select("id"));
  }
}
