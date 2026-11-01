package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

/** 原codec/节点与同DS/UoW的MP资格链；构造时不读取任务或资格。 */
final class StoredSessionScopeNormalization implements SessionScopeNormalization.Input {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task, step;
  private final String name, raw;

  StoredSessionScopeNormalization(
      JdbcSessionRecords records,
      Map<String, Object> task,
      Map<String, Object> step,
      String name,
      String raw) {
    this.records = records;
    this.task = task;
    this.step = step;
    this.name = name;
    this.raw = raw;
  }

  public String tool() {
    return name;
  }

  public String searchQuery() {
    return records.scopes.contracts(task).input(raw).query();
  }

  public SessionScopeNormalization.SearchObject searchObject() {
    var value = (ObjectNode) records.contracts.strictJson(raw, 4096);
    return new SessionScopeNormalization.SearchObject() {
      public void query(String query) {
        value.put("query", query);
      }

      public String sortedJson() {
        var sorted = new TreeMap<String, JsonNode>();
        value.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
        return Payloads.json(sorted);
      }
    };
  }

  public SessionScopeNormalization.Read read() {
    var input = records.contract(task).input(raw);
    return new SessionScopeNormalization.Read(
        input.sessionId(), input.seq(), input.before(), input.after());
  }

  public String caller() {
    return s(task, "id");
  }

  public long upper() {
    return n(step, "session_upper_seq");
  }

  public SessionScopeNormalization.Authority requireScope() {
    var scope = records.scopes.require(task);
    return target ->
        StreamSupport.stream(scope.authority().path("targets").spliterator(), false)
            .filter(t -> target.equals(t.path("session_id").asText()))
            .findFirst()
            .orElseThrow(JdbcSessionScopes::unavailable)
            .path("upper_seq")
            .asLong();
  }

  public String writeRead(String target, SessionScopeNormalization.Read read, long upper) {
    return Payloads.json(
        Map.of(
            "target_session_id", target,
            "seq", read.seq(),
            "before", read.before(),
            "after", read.after(),
            "upper_seq", upper));
  }
}
