package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 停止服务后直接逐18投影重计；不调用生产fixed-point或account实现。 */
public final class SessionIndependentBalanceScenario {
  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var source =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var ledger = new JdbcLedger(source, new ModelProfiles(env), null, null, null);
    var utc = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    for (String id : args[1].split(",")) {
      var task = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", id);
      var projection = new MapperSessionMetadataAccounting(ledger.sessions, task);
      long total = 0;
      var perTable = new LinkedHashMap<String, Long>();
      for (var table : SessionMetadataAccounting.Table.values()) {
        long bytes = 0;
        for (var row : projection.rows(table)) {
          bytes += 32;
          for (Object value : row.values()) {
            String raw =
                value instanceof LocalDateTime time ? utc.format(time) : String.valueOf(value);
            bytes += 8 + (value == null ? 1 : raw.getBytes(StandardCharsets.UTF_8).length);
          }
        }
        perTable.put(table.name(), bytes);
        total += bytes;
      }
      var history =
          db.queryForMap(
              "SELECT * FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?", id);
      if (total != ((Number) history.get("metadata_bytes")).longValue())
        throw new AssertionError(
            "Final metadata mismatch: " + id + " actual=" + total + " row=" + history);
      var balance =
          db.queryForMap(
              "SELECT h.metadata_bytes,h.body_bytes,h.held_bytes,h.reserved_bytes,r.amount,r.actual,r.state FROM task_session_history h JOIN reservation r ON r.tenant_id=h.tenant_id AND r.task_id=h.task_id AND r.category='SESSION_HISTORY_STORAGE' WHERE h.tenant_id='tenant-a' AND h.task_id=?",
              id);
      if (!"RESERVED".equals(balance.get("state"))
          || balance.get("actual") != null
          || ((Number) balance.get("amount")).longValue() != 8388608L
          || ((Number) balance.get("held_bytes")).longValue() != 8388608L
          || ((Number) balance.get("reserved_bytes")).longValue() != 8388608L)
        throw new AssertionError("PRESENT reserve must stay intact");
      System.out.println(
          "FINAL_BALANCE "
              + Payloads.json(
                  Map.of(
                      "task", id, "metadata", total, "projections", perTable, "balance", balance)));
    }
    long held =
        db.queryForObject(
            "SELECT COALESCE(SUM(held_bytes),0) FROM task_session_history WHERE tenant_id='tenant-a'",
            Long.class);
    long tenant =
        db.queryForObject(
            "SELECT history_storage_held FROM tenant_budget WHERE tenant_id='tenant-a'",
            Long.class);
    if (held != tenant) throw new AssertionError("Tenant history total mismatch");
    System.out.println("TENANT_BALANCE " + held);
  }
}
