package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionScopeQueryReadMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeReadRefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 同DS/UoW/query下的真实MP投影；保留冻结Schema和原始JSON节点。 */
final class MapperSessionScopeReadRefs implements SessionScopeReadRefs {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final String invocation;
  private List<JsonNode> nodes;

  MapperSessionScopeReadRefs(
      JdbcSessionRecords records, Map<String, Object> task, String invocation) {
    this.records = records;
    this.task = task;
    this.invocation = invocation;
  }

  public Binding binding() {
    var b =
        records.proofRow(
            HistoryInvocationSessionScopeQueryReadMapper.class,
            new QueryWrapper<>()
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("invocation_id", invocation)
                .eq("caller_task_id", s(task, "id"))
                .select(
                    "operation",
                    "outcome",
                    "read_refs_snapshot",
                    "read_refs_bytes",
                    "read_refs_digest"));
    return new Binding(
        s(b, "operation"),
        s(b, "outcome"),
        s(b, "read_refs_snapshot"),
        b.get("read_refs_bytes") == null ? null : Long.valueOf(n(b, "read_refs_bytes")),
        s(b, "read_refs_digest"));
  }

  public void validateRefs(String raw) {
    records.scopes.contracts.refs(raw);
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public void retainNodes(String raw) {
    var result = new ArrayList<JsonNode>();
    records.contracts.json(raw, 2048).forEach(result::add);
    nodes = List.copyOf(result);
  }

  List<JsonNode> nodes() {
    return nodes;
  }
}
