package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 受控恢复注入后真实claim新fence；新的JVM逐原槽返回旧字节，不能重扣消费。 */
public final class SessionLineageRecoveryScenario {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

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
    ledger.sessionReadEnabled(
        true,
        List.of(
            env.getRequiredProperty("jimu.identity.key"),
            env.getRequiredProperty("jimu.db.password")));
    ledger.sessionSearchEnabled(true);
    ledger.sessionTraceEnabled(true);
    ledger.priorSessionSearchEnabled(true);
    ledger.sessionLineageEnabled(true);
    String task = args[1];
    var original = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    check("SUCCEEDED".equals(original.get("state")), "Original complete task");
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE tenant_id='tenant-a' AND task_id=? AND quiescent=FALSE",
                Integer.class,
                task)
            == 0,
        "All original writers quiescent");
    var invocations =
        db.queryForList(
            "SELECT * FROM invocation WHERE tenant_id='tenant-a' AND task_id=? ORDER BY ordinal",
            task);
    check(invocations.size() == 3, "One response with exactly three trace slots");
    String cut =
        db.queryForList(
                "SELECT * FROM step_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                task)
            .toString();
    long receipts =
        db.queryForObject(
            "SELECT COUNT(*) FROM event WHERE task_id=? AND type='tool.completed'",
            Long.class,
            task);
    long charges =
        db.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE task_id=? AND category='TOOL' AND state='SETTLED' AND actual=1",
            Long.class,
            task);
    // 私有夹具显式制造恢复入口；新attempt/fence仍由实际claim生成，不伪造Lease。
    check(
        db.update(
                "UPDATE task SET state='RESUME_READY',ready_at=CURRENT_TIMESTAMP(6) WHERE tenant_id='tenant-a' AND id=? AND state='SUCCEEDED'",
                task)
            == 1,
        "Controlled recovery entry");
    String worker = "lineage-recovery-private";
    ledger.registerWorker(worker, worker, ProcessHandle.current().pid(), 1, "private", "private");
    var lease = ledger.claim(worker).orElseThrow();
    check(
        task.equals(lease.taskId()) && lease.fence() > ((Number) original.get("fence")).longValue(),
        "Real new fenced claim");
    for (var slot : invocations) {
      check(ledger.renew(lease), "Live new owner");
      String output =
          ledger.lineageOrResult(
              lease,
              (String) slot.get("step_id"),
              new Call(
                  (String) slot.get("slot"),
                  (String) slot.get("provider_id"),
                  (String) slot.get("tool"),
                  (String) slot.get("input_snapshot")),
              (String) slot.get("plugin_version"),
              (String) slot.get("plugin_digest"));
      check(
          output.equals(slot.get("output_snapshot"))
              && Payloads.hash(output).equals(slot.get("output_digest")),
          "Exact original output bytes");
    }
    check(
        db.queryForObject("SELECT tools_used FROM task WHERE id=?", Long.class, task)
            .equals(((Number) original.get("tools_used")).longValue()),
        "No extra task charge");
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM event WHERE task_id=? AND type='tool.completed'",
                Long.class,
                task)
            == receipts,
        "No extra receipts");
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE task_id=? AND category='TOOL' AND state='SETTLED' AND actual=1",
                Long.class,
                task)
            == charges,
        "No extra reservations");
    check(
        cut.equals(
            db.queryForList(
                    "SELECT * FROM step_session_scope WHERE tenant_id='tenant-a' AND caller_task_id=?",
                    task)
                .toString()),
        "Exact frozen cut");
    var current = db.queryForMap("SELECT * FROM task WHERE tenant_id='tenant-a' AND id=?", task);
    long exact = ledger.sessions.metadata(current, true);
    check(
        db.queryForObject(
                "SELECT metadata_bytes FROM task_session_history WHERE task_id=?", Long.class, task)
            == exact,
        "Independent final-row metadata recount");
    ledger.finish(lease, (String) original.get("summary"));
    ledger.quiescent(lease);
    ledger.processStopped(worker);
    System.out.println(
        "RECOVERY "
            + Payloads.json(
                Map.of(
                    "task",
                    task,
                    "oldFence",
                    original.get("fence"),
                    "newFence",
                    lease.fence(),
                    "exactOutputBytes",
                    true,
                    "cutUnchanged",
                    true,
                    "receipts",
                    receipts,
                    "charges",
                    charges,
                    "metadataRecount",
                    exact)));
  }
}
