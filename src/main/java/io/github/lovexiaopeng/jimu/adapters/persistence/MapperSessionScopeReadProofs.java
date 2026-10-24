package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryBindingReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryCutReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadProofs;
import java.util.List;
import java.util.Map;

/** 每次证明独立Map/JSON上下文；真实MP使用原DS/query及结果计量。 */
final class MapperSessionScopeReadProofs implements SessionScopeReadProofs.Proof {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, event;
  private final JsonNode value;
  private JsonNode source;
  private Map<String, Object> invocation, binding, step;

  MapperSessionScopeReadProofs(
      JdbcSessionRecords records,
      Map<String, Object> task,
      Map<String, Object> event,
      JsonNode value) {
    this.records = records;
    this.task = task;
    this.event = event;
    this.value = value;
  }

  public SessionScopeReadProofs.Event event() {
    return new SessionScopeReadProofs.Event(
        s(event, "fact_key"),
        n(event, "seq"),
        value.path("source").path("kind").asText(),
        Payloads.json(value.path("data")),
        Payloads.json(value.path("source_event_refs")));
  }

  private QueryWrapper<Object> base() {
    return new QueryWrapper<>().eq("tenant_id", s(task, "tenant_id"));
  }

  public SessionScopeReadProofs.Invocation invocation() {
    invocation =
        records.proofRow(
            HistoryInvocationReadMapper.class,
            base()
                .eq("task_id", s(task, "id"))
                .eq("id", s(event, "source_invocation_id"))
                .eq("step_id", s(event, "source_step_id"))
                .apply(
                    "(NOT {0} OR (OCTET_LENGTH(input_snapshot)<=4096 AND COALESCE(OCTET_LENGTH(output_snapshot),0)<=32768))",
                    records.priorProof())
                .select(
                    "id",
                    "step_id",
                    "tool",
                    "input_snapshot",
                    "input_digest",
                    "state",
                    "output_snapshot",
                    "output_digest"));
    return new SessionScopeReadProofs.Invocation(
        s(invocation, "id"),
        s(invocation, "step_id"),
        s(invocation, "tool"),
        s(invocation, "input_snapshot"),
        s(invocation, "input_digest"),
        s(invocation, "state"),
        s(invocation, "output_snapshot"),
        s(invocation, "output_digest"));
  }

  public SessionScopeReadProofs.Binding binding() {
    binding =
        records.proofRow(
            ScopeQueryBindingReadMapper.class,
            base()
                .eq("invocation_id", s(invocation, "id"))
                .eq("caller_task_id", s(task, "id"))
                .eq("step_id", s(invocation, "step_id"))
                .select(
                    "operation",
                    "authority_digest",
                    "context_digest",
                    "binding_digest",
                    "normalized_snapshot",
                    "normalized_digest",
                    "normalized_bytes",
                    "native_read_seq",
                    "outcome",
                    "input_digest",
                    "output_digest",
                    "output_bytes",
                    "failure_code"));
    return new SessionScopeReadProofs.Binding(
        s(binding, "operation"),
        s(binding, "authority_digest"),
        s(binding, "context_digest"),
        s(binding, "binding_digest"),
        s(binding, "normalized_snapshot"),
        s(binding, "normalized_digest"),
        n(binding, "normalized_bytes"),
        number(binding, "native_read_seq"),
        s(binding, "outcome"),
        s(binding, "input_digest"),
        s(binding, "output_digest"),
        number(binding, "output_bytes"));
  }

  private static Long number(Map<String, Object> map, String key) {
    return map.get(key) == null ? null : Long.valueOf(n(map, key));
  }

  public SessionScopeReadProofs.Step step() {
    step =
        records.proofRow(
            ScopeQueryStepReadMapper.class,
            base()
                .eq("task_id", s(task, "id"))
                .eq("id", s(invocation, "step_id"))
                .apply(
                    "(NOT {0} OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
                    records.priorProof())
                .select(
                    "id",
                    "task_id",
                    "record_version",
                    "session_upper_seq",
                    "request_snapshot",
                    "request_digest",
                    "session_context_digest",
                    "session_binding_digest"));
    return new SessionScopeReadProofs.Step(
        s(step, "session_context_digest"), s(step, "session_binding_digest"));
  }

  public void validateStep() {
    records.validateStep(task, step);
  }

  public String authority() {
    return s(records.scopes.require(task).row(), "authority_digest");
  }

  public SessionScopeReadProofs.Cut cut() {
    var cut =
        records.proofRow(
            ScopeQueryCutReadMapper.class,
            base()
                .eq("caller_task_id", s(task, "id"))
                .eq("step_id", s(invocation, "step_id"))
                .select("authority_digest", "context_digest", "context_snapshot"));
    return new SessionScopeReadProofs.Cut(
        s(cut, "authority_digest"), s(cut, "context_digest"), s(cut, "context_snapshot"));
  }

  public String normalizedInput() {
    return records.normalizedScope(
        task, step, "session_event_read", s(invocation, "input_snapshot"));
  }

  public boolean sameJson(String left, String right, int limit) {
    return records.contracts.json(left, limit).equals(records.contracts.json(right, limit));
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public String receipt() {
    return Payloads.json(records.scopeReceipt(binding));
  }

  public SessionScopeReadProofs.Refs refs() {
    var values = records.scopeReadRefs(task, s(invocation, "id"));
    return new SessionScopeReadProofs.Refs(
        Payloads.json(values),
        values.stream()
            .map(
                r ->
                    new SessionScopeReadProofs.Ref(
                        r.path("session_id").asText(),
                        r.path("seq").asLong(),
                        r.path("event_sha256").asText()))
            .toList());
  }

  public void validateOutput(String raw) {
    records.v4.output(raw);
  }

  public SessionScopeReadProofs.Output output(String raw) {
    var o = records.contracts.json(raw, 32768);
    return new SessionScopeReadProofs.Output(
        o.path("session_id").asText(),
        o.path("upper_seq").asLong(),
        o.path("window").path("from_seq").asLong(),
        o.path("window").path("to_seq").asLong(),
        Payloads.json(o.path("target")),
        parts(o.path("before")),
        parts(o.path("after")));
  }

  private static List<String> parts(JsonNode array) {
    var result = new java.util.ArrayList<String>();
    array.forEach(n -> result.add(Payloads.json(n)));
    return List.copyOf(result);
  }

  public SessionScopeReadProofs.Normalized normalized(String raw) {
    var n = records.contracts.json(raw, 4096);
    return new SessionScopeReadProofs.Normalized(
        n.path("target_session_id").asText(),
        n.path("seq").asLong(),
        n.path("before").asInt(),
        n.path("after").asInt(),
        n.path("upper_seq").asLong());
  }

  public List<SessionScopeReadProofs.Endpoint> endpoints(String session, long from, long to) {
    return records.scopeProofEndpoints(s(task, "tenant_id"), session, from, to).stream()
        .map(
            e ->
                new SessionScopeReadProofs.Endpoint(
                    n(e, "seq"), s(e, "event_snapshot"), s(e, "event_digest"), n(e, "event_bytes")))
        .toList();
  }

  public void validateEvent(String raw) {
    records.v4.event(raw);
  }

  public SessionScopeReadProofs.Source source(String raw) {
    source = records.contracts.json(raw, 16384);
    return new SessionScopeReadProofs.Source(
        source.path("session_id").asText(), source.path("seq").asLong(), Payloads.json(source));
  }

  public String sourceSummary() {
    return Payloads.json(JdbcSessionRecords.readSummary(source));
  }
}
