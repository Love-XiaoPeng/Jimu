package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 同一产品驱动对照 B2 前后 worker 收据、双领取、旧 fence、恢复及实际写后回滚。 */
public final class WorkerLeaseScenario {
  private WorkerLeaseScenario() {}

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      properties.load(stream);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private", properties));
    var raw =
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password"));
    var injected = new AtomicBoolean();
    DataSource source = observed(raw, injected);
    var ledger = new JdbcLedger(source, new ModelProfiles(env));
    var db = new JdbcTemplate(raw);
    String tag = args[1],
        host = "b2-" + tag,
        first = "b2-" + tag + "-one",
        second = "b2-" + tag + "-two";
    ledger.registerWorker(first, host, 101, 201, "fixture-boot", "fixture-namespace");
    ledger.registerWorker(second, host, 102, 202, "fixture-boot", "fixture-namespace");
    var processes = ledger.processes(host);
    check(
        processes.size() == 2
            && processes.stream().allMatch(p -> p.bootId().equals("fixture-boot")),
        "observed-process-coordinates");
    var owner = new Tasks.Identity("tenant-a", "alice");
    String task = submit(ledger, owner, "b2-" + tag + "-lease");
    var barrier = new CyclicBarrier(2);
    Lease lease;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return ledger.claim(first);
              });
      var b =
          executor.submit(
              () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return ledger.claim(second);
              });
      var left = a.get(20, TimeUnit.SECONDS);
      var right = b.get(20, TimeUnit.SECONDS);
      check(left.isPresent() != right.isPresent(), "one-concurrent-claim");
      lease = left.orElseGet(right::orElseThrow);
    }
    check(lease.taskId().equals(task) && ledger.renew(lease), "current-fence-renews");
    db.update(
        "UPDATE task SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=? AND id=?",
        owner.tenant(),
        task);
    check(!ledger.renew(lease), "expired-lease-cannot-renew");
    var replacement = ledger.claim(lease.worker().equals(first) ? second : first).orElseThrow();
    check(
        replacement.taskId().equals(task) && replacement.fence() == lease.fence() + 1,
        "replacement-increments-fence");
    check(!ledger.renew(lease) && ledger.renew(replacement), "old-fence-cannot-renew");
    ledger.quiescent(lease);
    check(
        db.queryForObject(
            "SELECT quiescent FROM task_attempt WHERE tenant_id=? AND id=?",
            Boolean.class,
            owner.tenant(),
            lease.attemptId()),
        "old-attempt-quiescent");
    check(
        !db.queryForObject(
            "SELECT quiescent FROM task_attempt WHERE tenant_id=? AND id=?",
            Boolean.class,
            owner.tenant(),
            replacement.attemptId()),
        "new-writer-stays-active");
    ledger.cancel(owner, task);
    check(ledger.get(owner, task).state().equals("CANCEL_REQUESTED"), "cancel-awaits-writer");
    ledger.processStopped(replacement.worker());
    ledger.processStopped(lease.worker());
    check(ledger.get(owner, task).state().equals("CANCELLED"), "stopped-writer-settles-cancel");
    check(
        db.queryForObject(
                "SELECT state FROM reservation WHERE tenant_id=? AND id=?",
                String.class,
                owner.tenant(),
                task + ":run")
            .equals("RELEASED"),
        "run-released-once");
    ledger.processStopped(replacement.worker());
    check(
        db.queryForObject(
                "SELECT running FROM tenant_budget WHERE tenant_id=?",
                Integer.class,
                owner.tenant())
            == 0,
        "repeated-stop-no-double-release");
    check(ledger.processes(host).isEmpty(), "stopped-processes-hidden");
    String rollback = submit(ledger, owner, "b2-" + tag + "-rollback");
    var before =
        db.queryForMap(
            "SELECT queued,running FROM tenant_budget WHERE tenant_id=?", owner.tenant());
    injected.set(true);
    try {
      ledger.claim(first);
      throw new AssertionError("write fault missing");
    } catch (org.springframework.dao.DataAccessException failure) {
      check(!injected.get(), "fault-after-real-attempt-insert");
    }
    check(
        before.equals(
            db.queryForMap(
                "SELECT queued,running FROM tenant_budget WHERE tenant_id=?", owner.tenant())),
        "claim-budget-rollback");
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE tenant_id=? AND task_id=?",
                Integer.class,
                owner.tenant(),
                rollback)
            == 0,
        "claim-attempt-rollback");
    check(
        db.queryForObject(
                "SELECT state FROM reservation WHERE tenant_id=? AND id=?",
                String.class,
                owner.tenant(),
                rollback + ":queue")
            .equals("RESERVED"),
        "claim-reservation-rollback");
    ledger.cancel(owner, rollback);
    System.out.println("RESULT worker receipts/race/expiry/fence/recovery/cancel/rollback = PASS");
  }

  private static String submit(JdbcLedger ledger, Tasks.Identity owner, String goal) {
    return ledger
        .submit(
            owner,
            goal,
            new Tasks.Request(
                goal, List.of("web_fetch"), List.of(), new Tasks.Budget(180, 8, 20, null)))
        .taskId();
  }

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }

  private static DataSource observed(DataSource raw, AtomicBoolean injected) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              try {
                Object result = method.invoke(raw, args);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, m, a) -> {
                      try {
                        Object value = m.invoke(connection, a);
                        if (!(value instanceof PreparedStatement statement)
                            || !m.getName().equals("prepareStatement")
                            || !((String) a[0]).startsWith("INSERT INTO task_attempt"))
                          return value;
                        return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (ps, op, bind) -> {
                              try {
                                Object answer = op.invoke(statement, bind);
                                if (op.getName().startsWith("execute")
                                    && injected.compareAndSet(true, false))
                                  throw new SQLException("private-after-write", "45000", 1644);
                                return answer;
                              } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                              }
                            });
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
