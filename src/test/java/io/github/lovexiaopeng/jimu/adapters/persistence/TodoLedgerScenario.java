package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only direct boundary driver; credentials stay in the private fixture config. */
public final class TodoLedgerScenario {
  private TodoLedgerScenario() {}

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var values = new HashMap<String, Object>();
    properties.forEach((k, v) -> values.put(k.toString(), v));
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("fixture", values));
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.user"),
            properties.getProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    String taskId = args[1], mode = args[2];
    int ordinal = Integer.parseInt(args[3]);
    var task = db.queryForMap("SELECT * FROM task WHERE id=?", taskId);
    String tenant = (String) task.get("tenant_id");
    if (mode.equals("lock")) {
      try (var c = source.getConnection()) {
        c.setAutoCommit(false);
        try (var statement =
            c.prepareStatement(
                "SELECT tenant_id FROM tenant_budget WHERE tenant_id=? FOR UPDATE")) {
          statement.setString(1, tenant);
          statement.executeQuery().close();
        }
        System.out.println("LOCKED");
        System.out.flush();
        System.in.read();
        c.rollback();
      }
      return;
    }
    var invocation =
        db.queryForMap(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? ORDER BY ordinal LIMIT 1 OFFSET "
                + ordinal,
            tenant,
            taskId);
    var lease =
        new Lease(
            tenant,
            (String) task.get("owner_actor_id"),
            taskId,
            (String) task.get("current_attempt_id"),
            ((Number) task.get("fence")).longValue(),
            (String) task.get("worker_id"),
            ((java.time.LocalDateTime) task.get("deadline")).toInstant(ZoneOffset.UTC),
            (String) task.get("goal"),
            java.util.List.of("todo_write"),
            ((Number) task.get("installation_epoch")).longValue());
    String step = (String) invocation.get("step_id");
    var call =
        new Call(
            (String) invocation.get("slot"),
            (String) invocation.get("provider_id"),
            (String) invocation.get("tool"),
            (String) invocation.get("input_snapshot"));
    if (mode.equals("old-fence"))
      lease =
          new Lease(
              lease.tenant(),
              lease.actor(),
              lease.taskId(),
              lease.attemptId(),
              lease.fence() - 1,
              lease.worker(),
              lease.deadline(),
              lease.goal(),
              lease.capabilities(),
              lease.installationEpoch());
    if (mode.equals("actor"))
      lease =
          new Lease(
              lease.tenant(),
              "forged-actor",
              lease.taskId(),
              lease.attemptId(),
              lease.fence(),
              lease.worker(),
              lease.deadline(),
              lease.goal(),
              lease.capabilities(),
              lease.installationEpoch());
    if (mode.equals("step")) step = java.util.UUID.randomUUID().toString();
    if (mode.equals("slot"))
      call = new Call("99:forged-slot", call.providerId(), call.tool(), call.input());
    if (mode.equals("provider"))
      call = new Call(call.slot(), "forged-provider", call.tool(), call.input());
    if (mode.equals("input"))
      call = new Call(call.slot(), call.providerId(), call.tool(), call.input() + " ");
    var lost = new AtomicBoolean(mode.equals("lost-ack"));
    var wrapped =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws SQLException {
            Connection connection = source.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                      try {
                        Object result = method.invoke(connection, arguments);
                        if (method.getName().equals("commit") && lost.compareAndSet(true, false))
                          throw new SQLException("Test-only lost commit acknowledgement", "08007");
                        return result;
                      } catch (InvocationTargetException error) {
                        throw error.getCause();
                      }
                    });
          }
        };
    var ledger = new JdbcLedger(wrapped, new ModelProfiles(env));
    ledger.todosEnabled(true);
    try {
      if (mode.equals("admit"))
        ledger.admit(
            lease,
            step,
            call,
            (String) invocation.get("plugin_version"),
            (String) invocation.get("plugin_digest"));
      else if (mode.equals("succeed"))
        ledger.toolSucceeded(lease, (String) invocation.get("id"), "{}");
      else if (mode.equals("uncertain")) ledger.uncertain(lease, (String) invocation.get("id"));
      else {
        var result =
            ledger.replaceOrResult(
                lease,
                step,
                call,
                (String) invocation.get("plugin_version"),
                (String) invocation.get("plugin_digest"));
        System.out.println(Payloads.json(java.util.Map.of("code", "OK", "result", result)));
        return;
      }
      System.out.println("{\"code\":\"BYPASS_ACCEPTED\"}");
    } catch (TaskFailure failure) {
      System.out.println(Payloads.json(java.util.Map.of("code", failure.code())));
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unconfirmed) {
      System.out.println("{\"code\":\"UNCONFIRMED\"}");
    }
  }
}
