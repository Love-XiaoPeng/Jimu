package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.protocol.StandardSocketFactory;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryWork;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 私有MySQL真实ACK在socket读取层吞弃；模拟SQLException只用于明确标记的setup点。 */
public final class SessionOwnerPhaseScenario {
  private static boolean armed;
  private static int swallowed;
  private static String mode;
  private static final List<String> wire = new ArrayList<>();

  public static final class AckSocketFactory extends StandardSocketFactory {
    @Override
    protected Socket createSocket(PropertySet properties) {
      return new Socket() {
        @Override
        public InputStream getInputStream() throws IOException {
          return new FilterInputStream(super.getInputStream()) {
            private void received(int bytes) throws IOException {
              if (armed && bytes > 0) {
                armed = false;
                swallowed += bytes;
                wire.add("server ACK received then discarded bytes=" + bytes);
                close();
                throw new SocketException("private fixture discarded actual server ACK");
              }
            }

            @Override
            public int read() throws IOException {
              int value = in.read();
              received(value < 0 ? 0 : 1);
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              int count = in.read(bytes, offset, length);
              received(count);
              return count;
            }
          };
        }
      };
    }
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      properties.load(input);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url")
                + "&useSSL=false&socketFactory="
                + AckSocketFactory.class.getName()
                + "&enableQueryTimeouts=false",
            properties.getProperty("jimu.db.migration-user"),
            properties.getProperty("jimu.db.migration-password"));
    var independent =
        new JdbcTemplate(
            new DriverManagerDataSource(
                properties.getProperty("jimu.db.url"),
                properties.getProperty("jimu.db.migration-user"),
                properties.getProperty("jimu.db.migration-password")));
    independent.execute(
        "CREATE TABLE xiao126_owner_probe(id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
    independent.update("INSERT INTO xiao126_owner_probe VALUES(1,0)");
    var wrapped =
        new DelegatingDataSource(source) {
          @Override
          public Connection getConnection() throws java.sql.SQLException {
            Connection original = super.getConnection();
            long id;
            try (var statement = original.createStatement();
                var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
              result.next();
              id = result.getLong(1);
            }
            wire.add("connection=" + id);
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, parameters) -> {
                      String operation = method.getName();
                      if (operation.equals("commit") || operation.equals("rollback")) {
                        wire.add(operation + " entered connection=" + id);
                        if (mode.equals(operation + "-ack")) armed = true;
                        Object value = invoke(original, method, parameters);
                        wire.add(operation + " returned connection=" + id);
                        return value;
                      }
                      if (operation.equals("setNetworkTimeout") && mode.equals("setup"))
                        throw new java.sql.SQLException("synthetic setup fault; not an ACK test");
                      return invoke(original, method, parameters);
                    });
          }
        };
    var db = new JdbcTemplate(wrapped);
    check(args[1].matches("[a-f0-9-]{36}"), "Exact private task id");
    String taskLock =
        "SELECT id FROM task WHERE tenant_id='tenant-a' AND id='" + args[1] + "' FOR UPDATE";
    var transaction =
        new SessionQueryTransaction(
            wrapped, new org.springframework.jdbc.datasource.DataSourceTransactionManager(wrapped));
    for (String phase :
        List.of(
            "setup",
            "business",
            "beforeCommit",
            "budget-lock",
            "task-lock",
            "commit-ack",
            "rollback-ack")) {
      mode = phase;
      armed = false;
      swallowed = 0;
      wire.clear();
      var context = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
      SessionQueryWork[] held = {null};
      Throwable caught = null;
      Connection blocker = null;
      if (phase.endsWith("-lock")) {
        blocker = source.getConnection();
        blocker.setAutoCommit(false);
        try (var statement = blocker.createStatement()) {
          statement
              .executeQuery(
                  phase.equals("budget-lock")
                      ? "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE"
                      : taskLock)
              .close();
        }
      }
      try {
        transaction.required(
            context,
            () -> {
              held[0] = SessionQueryTransaction.current(context);
              if (phase.endsWith("-lock")) {
                db.execute("SET SESSION innodb_lock_wait_timeout=5");
                db.queryForList(
                    phase.equals("budget-lock")
                        ? "SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE"
                        : taskLock);
              }
              db.update("UPDATE xiao126_owner_probe SET value=value+1 WHERE id=1");
              wire.add(
                  "transaction="
                      + independent.queryForList(
                          "SELECT trx_id,trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_query IS NULL"));
              if (phase.equals("business")) context.restrictDeadline(System.nanoTime() - 1);
              if (phase.equals("beforeCommit"))
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      public int getOrder() {
                        return Integer.MIN_VALUE;
                      }

                      public void beforeCommit(boolean readOnly) {
                        context.restrictDeadline(System.nanoTime() - 1);
                      }
                    });
              if (phase.equals("rollback-ack"))
                throw new IllegalStateException("fixture rollback work failure");
              context.remainingMillis();
              return null;
            });
      } catch (RuntimeException failure) {
        caught = failure;
      } finally {
        armed = false;
        if (blocker != null) {
          blocker.rollback();
          blocker.close();
        }
      }
      check(caught != null, "Every phase must fail locally: " + phase);
      int visible =
          independent.queryForObject(
              "SELECT value FROM xiao126_owner_probe WHERE id=1", Integer.class);
      if (phase.endsWith("-ack")) {
        check(
            caught instanceof SessionTransactionUnconfirmed, "Real swallowed ACK remains UNKNOWN");
        check(swallowed > 0, "Actual socket response bytes discarded");
        check(
            wire.stream()
                .noneMatch(
                    line -> line.startsWith(phase.substring(0, phase.indexOf('-')) + " returned")),
            "No JDBC completion return");
        check(
            visible == (phase.equals("commit-ack") ? 1 : 0),
            "Independent connection sees real server result");
      } else check(visible == 0, "No phase leaks a write");
      if (held[0] != null) {
        try {
          held[0].token();
          throw new AssertionError("Leaked proof owner");
        } catch (IllegalStateException expected) {
          /* completion released authority */
        }
      }
      System.out.println(
          "OWNER_PHASE "
              + phase
              + " outcome="
              + (caught instanceof SessionTransactionUnconfirmed unknown
                  ? unknown.outcome()
                  : "confirmed local failure " + caught.getClass().getSimpleName())
              + " independentValue="
              + visible
              + " events="
              + wire);
      independent.update("UPDATE xiao126_owner_probe SET value=0 WHERE id=1");
    }
    independent.execute("DROP TABLE xiao126_owner_probe");
  }
}
