package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionPublicEventAppendEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionPublicEventAppendMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventAppend;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same DS/UoW; codec and original JSON nodes/NULLs are adapter-owned. */
final class MapperSessionEventAppend implements SessionEventAppend {
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, source, data;
  private final String fact, type, sourceDigest;
  private final boolean redacted;
  private final Lease lease;
  private final long reliable;

  MapperSessionEventAppend(
      JdbcSessionRecords records,
      Map<String, Object> task,
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String sourceDigest,
      Lease lease,
      long reliable) {
    this.records = records;
    this.task = task;
    this.fact = fact;
    this.type = type;
    this.source = source;
    this.data = data;
    this.redacted = redacted;
    this.sourceDigest = sourceDigest;
    this.lease = lease;
    this.reliable = reliable;
  }

  private record FrozenEvent(LocalDateTime time, String raw) implements Event {
    public int bytes() {
      return SessionContracts.bytes(raw);
    }
  }

  public boolean terminal() {
    return type.equals("turn.ended");
  }

  public History history(boolean readable) {
    var h =
        fact.equals("accepted-user")
            ? records.acceptedEventHistory(task, readable)
            : records.history(task, readable);
    return () -> n(h, "next_seq");
  }

  public Long prior() {
    var prior =
        records.bindingRows(
            SessionPublicEventAppendMapper.class,
            new QueryWrapper<SessionPublicEventAppendEntity>()
                .select("seq")
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("task_id", s(task, "id"))
                .eq("fact_key", fact));
    return prior.isEmpty() ? null : n(prior.getFirst(), "seq");
  }

  public Event event(long seq) {
    LocalDateTime time = records.eventAppendMapper().clock(records.appendQuery());
    var value = new LinkedHashMap<String, Object>();
    value.put("version", records.version(task));
    value.put("session_id", s(task, "id"));
    value.put("seq", seq);
    value.put("type", type);
    value.put("time", UTC.format(time));
    value.put("source", source);
    value.put("content_policy", "public-v1");
    value.put("redacted", redacted);
    value.put("data", data);
    if (JdbcSessionScopes.task(task))
      value.put(
          "source_event_refs",
          type.equals("session.read")
              ? records.scopeReadRefs(task, source.get("invocation_id").toString())
              : List.of());
    if (JdbcSessionRecords.traceTask(task))
      value.put(
          "source_event_seqs",
          type.equals("session.read")
              ? records.readSources(task, source.get("invocation_id").toString()).stream()
                  .map(v -> v.path("seq").asLong())
                  .toList()
              : List.of());
    String raw = Payloads.json(value);
    return new FrozenEvent(time, raw);
  }

  public void validate(Event event) {
    records.contract(task).event(((FrozenEvent) event).raw());
  }

  public void body(int bytes) {
    records.body(task, bytes);
  }

  public void insert(long seq, Event event) {
    var frozen = (FrozenEvent) event;
    records.insertBinding(
        SessionPublicEventAppendMapper.class,
        new SessionPublicEventAppendEntity(
            s(task, "tenant_id"),
            s(task, "id"),
            records.version(task),
            seq,
            fact,
            type,
            frozen.time(),
            frozen.raw(),
            Payloads.hash(frozen.raw()),
            SessionContracts.bytes(frozen.raw()),
            sourceDigest,
            source.get("step_id"),
            source.get("invocation_id"),
            lease == null ? null : lease.attemptId(),
            lease == null ? null : lease.fence(),
            reliable));
  }

  public void advance() {
    records.eventAppendMapper().advance(records.appendQuery(), s(task, "tenant_id"), s(task, "id"));
  }
}
