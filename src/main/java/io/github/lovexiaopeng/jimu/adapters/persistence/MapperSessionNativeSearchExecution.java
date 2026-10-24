package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchMatcher;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeSearchExecution;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同DS/UoW的原生有界扫描；原matcher、codec、JsonNode和scan字节序留在适配器。 */
final class MapperSessionNativeSearchExecution implements SessionNativeSearchExecution {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final SessionEventSearchPort.Input input;
  private final long upper;
  private final String boundary;
  private final Runnable deadline;

  MapperSessionNativeSearchExecution(
      JdbcSessionRecords records,
      Map<String, Object> task,
      SessionEventSearchPort.Input input,
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

  private record JsonValue(JsonNode raw) implements Value {}

  public History history() {
    var history = records.history(task, true);
    return () -> n(history, "next_seq");
  }

  public boolean enabled() {
    return JdbcSessionRecords.searchTask(task);
  }

  public void normalized() {
    records.searchContract(task).normalized(input, s(task, "id"), upper);
  }

  public Matcher matcher() {
    var original = new SessionSearchMatcher(input, deadline);
    return new Matcher() {
      public void accept(Value value, String digest, long bound) {
        original.accept(((JsonValue) value).raw(), digest, bound);
      }

      public String output() {
        return original.output(s(task, "id"), upper, boundary, records.version(task));
      }
    };
  }

  public Budget budget() {
    return records.nativeSearchBudget(deadline);
  }

  public Long from() {
    return input.seqFrom();
  }

  public Long to() {
    return input.seqTo();
  }

  public long upper() {
    return upper;
  }

  public List<Event> scan() {
    return records.nativeSearchScan(s(task, "tenant_id"), s(task, "id"), upper).stream()
        .<Event>map(
            row ->
                new Event() {
                  public long seq() {
                    return n(row, "seq");
                  }

                  public Value verify() {
                    return new JsonValue(records.verify(task, row));
                  }

                  public int snapshotBytes() {
                    return SessionContracts.bytes(s(row, "event_snapshot"));
                  }

                  public String digest() {
                    return s(row, "event_digest");
                  }
                })
        .toList();
  }

  public void deadline() {
    deadline.run();
  }

  public void validate(String output) {
    records.searchContract(task).output(output);
  }

  public String scanDigest(List<Scan> scanned) {
    return Payloads.hash(
        Payloads.json(
            scanned.stream()
                .map(
                    scan -> {
                      var value = new LinkedHashMap<String, Object>();
                      value.put("seq", scan.seq());
                      value.put("event_sha256", scan.digest());
                      return value;
                    })
                .toList()));
  }
}
