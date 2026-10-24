package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReceipts;
import java.util.LinkedHashMap;
import java.util.Map;

/** 已有MP/Mapper行的惰性值适配；没有查询、事务、写入或normalized重编码。 */
final class StoredSessionReceipts implements SessionReceipts.Native, SessionReceipts.Scope {
  private final JdbcSessionRecords records;
  private final Map<String, Object> row;
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonNode normalized;

  StoredSessionReceipts(JdbcSessionRecords records, Map<String, Object> row) {
    this.records = records;
    this.row = row;
  }

  public long target() {
    return n(row, "target_seq");
  }

  public long before() {
    return n(row, "before_count");
  }

  public long after() {
    return n(row, "after_count");
  }

  public long upper() {
    return n(row, "upper_seq");
  }

  public String outcome() {
    return s(row, "outcome");
  }

  public String code() {
    return s(row, "failure_code");
  }

  public String digest() {
    return s(row, "output_digest");
  }

  public long bytes() {
    return n(row, "output_bytes");
  }

  public void number(SessionReceipts.Field field, long value) {
    result.put(field.wire(), value);
  }

  public void text(SessionReceipts.Field field, String value) {
    result.put(field.wire(), value);
  }

  public void parseNormalized() {
    normalized = records.contracts.json(s(row, "normalized_snapshot"), 4096);
  }

  public void retainNode(SessionReceipts.Field field) {
    result.put(field.wire(), normalized.get(field.wire()));
  }

  Map<String, Object> result() {
    return result;
  }
}
