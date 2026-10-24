package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistorySessionPublicEventReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.NativeReadReceiptMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReadRelationProofs;
import java.util.List;
import java.util.Map;

/** 原DataSource/UoW/query包络内的真实MP投影；来源原JSON留给冻结事件写入。 */
final class MapperSessionReadRelationProofs implements SessionReadRelationProofs.Proof {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, row, invocation, binding;
  private final JsonNode event;
  private final String invocationId;
  private Map<String, Object> step;
  private List<JsonNode> sourceNodes;

  MapperSessionReadRelationProofs(
      JdbcSessionRecords records, Map<String, Object> task, String invocationId) {
    this(records, task, null, null, null, null, invocationId);
  }

  MapperSessionReadRelationProofs(
      JdbcSessionRecords records,
      Map<String, Object> task,
      Map<String, Object> row,
      JsonNode event,
      Map<String, Object> invocation,
      Map<String, Object> binding) {
    this(records, task, row, event, invocation, binding, s(invocation, "id"));
  }

  private MapperSessionReadRelationProofs(
      JdbcSessionRecords records,
      Map<String, Object> task,
      Map<String, Object> row,
      JsonNode event,
      Map<String, Object> invocation,
      Map<String, Object> binding,
      String invocationId) {
    this.records = records;
    this.task = task;
    this.row = row;
    this.event = event;
    this.invocation = invocation;
    this.binding = binding;
    this.invocationId = invocationId;
  }

  private QueryWrapper<Object> base() {
    return new QueryWrapper<>().eq("tenant_id", s(task, "tenant_id")).eq("task_id", s(task, "id"));
  }

  public SessionReadRelationProofs.SourceBinding sourceBinding() {
    var b =
        records.proofRow(
            NativeReadReceiptMapper.class,
            base()
                .eq("invocation_id", invocationId)
                .select(
                    "relation_version",
                    "content_state",
                    "read_sources_snapshot",
                    "read_sources_bytes",
                    "read_sources_digest"));
    return new SessionReadRelationProofs.SourceBinding(
        number(b, "relation_version"),
        s(b, "content_state"),
        s(b, "read_sources_snapshot"),
        number(b, "read_sources_bytes"),
        s(b, "read_sources_digest"));
  }

  public SessionReadRelationProofs.SourceDocument sourceDocument(String raw) {
    var document = records.contracts.json(raw, 1024);
    var nodes = new java.util.ArrayList<JsonNode>();
    document.forEach(nodes::add);
    sourceNodes = List.copyOf(nodes);
    return new SessionReadRelationProofs.SourceDocument(
        document.isArray(),
        document.size(),
        sourceNodes.stream()
            .map(
                v ->
                    new SessionReadRelationProofs.Source(
                        v.isObject(),
                        v.size(),
                        v.path("seq").isIntegralNumber(),
                        v.path("seq").asLong(),
                        v.path("event_sha256").asText()))
            .toList());
  }

  List<JsonNode> sourceNodes() {
    return sourceNodes;
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public String sequences(List<SessionReadRelationProofs.Source> values) {
    return Payloads.json(values.stream().map(SessionReadRelationProofs.Source::seq).toList());
  }

  public String eventSequences() {
    return Payloads.json(event.path("source_event_seqs"));
  }

  public SessionReadRelationProofs.Step step() {
    step =
        records.proofRow(
            ScopeQueryStepReadMapper.class,
            base()
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
                    "session_binding_digest",
                    "response_snapshot",
                    "response_digest"));
    return new SessionReadRelationProofs.Step(
        number(step, "session_upper_seq"),
        s(step, "response_snapshot"),
        s(step, "response_digest"));
  }

  public void validateStep() {
    records.validateStep(task, step);
  }

  public String historyBinding() {
    return s(records.history(task, true), "binding_digest");
  }

  public SessionReadRelationProofs.Invocation invocation() {
    return new SessionReadRelationProofs.Invocation(
        s(invocation, "input_snapshot"),
        s(invocation, "input_digest"),
        s(invocation, "tool"),
        s(invocation, "slot"),
        s(invocation, "provider_id"),
        s(invocation, "output_snapshot"),
        s(invocation, "plugin_version"),
        s(invocation, "plugin_digest"),
        number(invocation, "installation_epoch"));
  }

  public SessionReadRelationProofs.Binding binding() {
    return new SessionReadRelationProofs.Binding(
        s(binding, "binding_digest"),
        number(binding, "upper_seq"),
        s(binding, "input_digest"),
        number(binding, "target_seq"),
        number(binding, "before_count"),
        number(binding, "after_count"),
        s(binding, "outcome"),
        number(binding, "output_bytes"),
        number(binding, "window_start"),
        number(binding, "window_end"),
        s(binding, "target_digest"));
  }

  private static Long number(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : Long.valueOf(n(row, key));
  }

  public SessionReadRelationProofs.Input input(String raw) {
    var i = records.contract(task).input(raw);
    return new SessionReadRelationProofs.Input(i.sessionId(), i.seq(), i.before(), i.after());
  }

  public String session() {
    return s(task, "id");
  }

  public SessionReadRelationProofs.Response response(String raw) {
    var r = Payloads.read(raw, DurableAgent.Response.class);
    return new SessionReadRelationProofs.Response(
        r.version(),
        r.calls().stream()
            .map(c -> new SessionReadRelationProofs.Call(c.id(), c.name(), c.arguments()))
            .toList());
  }

  public SessionReadRelationProofs.Pin pin() {
    var pins =
        Payloads.read(
            s(task, "tools_snapshot"),
            io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot.class);
    var pin = pins.pins().get("session_event_read");
    return pin == null
        ? null
        : new SessionReadRelationProofs.Pin(pin.version(), pin.digest(), pin.installationEpoch());
  }

  public void validateOutput(String raw) {
    records.contract(task).output(raw);
  }

  public SessionReadRelationProofs.Output output(String raw) {
    var o = records.contracts.json(raw, 32768);
    return new SessionReadRelationProofs.Output(
        o.path("upper_seq").asLong(),
        o.path("boundary").asText(),
        o.path("session_id").asText(),
        o.path("target").path("seq").asLong(),
        o.path("window").path("from_seq").asLong(),
        o.path("window").path("to_seq").asLong(),
        o.path("before").size(),
        o.path("after").size(),
        Payloads.json(o.path("target")));
  }

  public long eventSeq() {
    return n(row, "seq");
  }

  public String endpointDigest(long seq) {
    return s(
        records.proofRow(
            HistorySessionPublicEventReadMapper.class,
            base().eq("seq", seq).eq("content_state", "PRESENT").select("event_digest")),
        "event_digest");
  }
}
