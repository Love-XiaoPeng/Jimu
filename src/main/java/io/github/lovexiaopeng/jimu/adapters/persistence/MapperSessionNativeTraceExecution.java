package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistorySessionPublicEventReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.NativeReadReceiptMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同DS/UoW/query的原生trace适配，保留原Map.of/JsonNode与摘要字节。 */
final class MapperSessionNativeTraceExecution implements SessionNativeTraceExecution {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final SessionEventTracePort.Input input;
  private final long upper;
  private final String boundary;
  private final Runnable deadline;

  MapperSessionNativeTraceExecution(
      JdbcSessionRecords records,
      Map<String, Object> task,
      SessionEventTracePort.Input input,
      long upper,
      String boundary,
      Runnable deadline) {
    this.records = records;
    this.task = task;
    this.input = input;
    this.upper = upper;
    this.boundary = boundary;
    this.deadline = deadline;
  }

  private record JsonValue(JsonNode raw) implements Value {
    public Iterable<Source> sources() {
      var list = new ArrayList<Source>();
      raw.path("source_event_seqs").forEach(v -> list.add(v::asLong));
      return list;
    }

    public int sourceCount() {
      return raw.path("source_event_seqs").size();
    }
  }

  private record JsonSummary(Map<String, Object> raw) implements Summary {
    public int bytes() {
      return SessionContracts.bytes(Payloads.json(raw));
    }
  }

  public History history() {
    var h = records.history(task, true);
    return () -> n(h, "next_seq");
  }

  public boolean enabled() {
    return JdbcSessionRecords.traceTask(task);
  }

  public String requestedSession() {
    return input.sessionId();
  }

  public String session() {
    return s(task, "id");
  }

  public long seq() {
    return input.seq();
  }

  public long upper() {
    return upper;
  }

  private QueryWrapper<Object> key() {
    return new QueryWrapper<>().eq("tenant_id", s(task, "tenant_id")).eq("task_id", s(task, "id"));
  }

  public boolean exists() {
    return !records
        .bindingRows(
            HistorySessionPublicEventReadMapper.class, key().eq("seq", input.seq()).select("seq"))
        .isEmpty();
  }

  public Budget budget() {
    return records.nativeTraceBudget(deadline);
  }

  public List<Event> scan() {
    return records.nativeSearchScan(s(task, "tenant_id"), s(task, "id"), upper).stream()
        .<Event>map(
            row ->
                new Event() {
                  public long seq() {
                    return n(row, "seq");
                  }

                  public int snapshotBytes() {
                    return SessionContracts.bytes(s(row, "event_snapshot"));
                  }

                  public Value verify() {
                    return new JsonValue(records.verify(task, row));
                  }

                  public String digest() {
                    return s(row, "event_digest");
                  }

                  public boolean read() {
                    return "session.read".equals(s(row, "type"));
                  }

                  public Receipt receipt() {
                    var b =
                        records.proofRow(
                            NativeReadReceiptMapper.class,
                            key()
                                .eq("invocation_id", s(row, "source_invocation_id"))
                                .select("read_sources_digest", "outcome"));
                    return new Receipt(s(b, "read_sources_digest"), s(b, "outcome"));
                  }

                  public int characters() {
                    return s(row, "event_snapshot").length();
                  }
                })
        .toList();
  }

  public void deadline() {
    deadline.run();
  }

  public Summary summary(Value value, String digest) {
    var target = ((JsonValue) value).raw();
    var summary = new LinkedHashMap<String, Object>();
    for (String key : List.of("seq", "type", "time", "source", "redacted"))
      summary.put(key, target.get(key));
    String text = target.path("data").path("content").asText(""),
        prefix = SessionContracts.prefix(text);
    summary.put("text", prefix);
    summary.put("summary_truncated", !text.equals(prefix));
    summary.put("event_sha256", digest);
    return new JsonSummary(summary);
  }

  public String output(Summary summary, Value target, List<Long> derived) {
    var output = new LinkedHashMap<String, Object>();
    output.put("version", 1);
    output.put("session_id", session());
    output.put("history_version", 3);
    output.put("content_policy", "public-v1");
    output.put("boundary", boundary);
    output.put("upper_seq", upper);
    output.put("relation_policy", SessionTraceContracts.POLICY);
    output.put("replacement_policy", "unsupported");
    output.put("target", ((JsonSummary) summary).raw());
    output.put("source_event_seqs", ((JsonValue) target).raw().path("source_event_seqs"));
    output.put("derived_event_seqs", derived);
    return Payloads.json(output);
  }

  public void validate(String raw) {
    records.trace.output(raw);
  }

  public String scanDigest(List<Scan> scan) {
    return Payloads.hash(
        Payloads.json(
            scan.stream().map(v -> Map.of("seq", v.seq(), "event_sha256", v.digest())).toList()));
  }

  public String relationsDigest(List<Relation> relations) {
    return Payloads.hash(
        Payloads.json(
            relations.stream()
                .map(v -> Map.of("seq", v.seq(), "read_sources_sha256", v.digest()))
                .toList()));
  }
}
