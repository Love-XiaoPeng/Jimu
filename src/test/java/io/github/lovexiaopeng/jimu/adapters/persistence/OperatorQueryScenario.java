package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 独立JVM验证真实同DS事务、只读RR一致快照、结果实计与不可重置的绝对截止。 */
public final class OperatorQueryScenario {
  private OperatorQueryScenario() {}

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    var source =
        new DriverManagerDataSource(
            properties.getProperty("jimu.db.url"),
            properties.getProperty("jimu.db.migration-user"),
            properties.getProperty("jimu.db.migration-password"));
    var session = LedgerMapperSessions.createOperator(source);
    var query =
        new SessionQueryContext(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), new QueryResources(0, 0, 0));
    var binding = new MapperScopeBindingRecords(session, query);
    var resultRecords = new MapperWebResultRecords(session, query);
    new OperatorTransactions(source, false)
        .required(
            () -> {
              require(binding.lockRun("tenant-a", args[1]) != null, "actual evaluation lock");
              require(binding.binding("tenant-a", args[1]) != null, "real MP scope projection");
              require(binding.receipts("tenant-a").size() == 1, "read-only external receipt");
              return null;
            });
    String original =
        new JdbcTemplate(source)
            .queryForObject(
                "SELECT output_digest FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
                String.class,
                "tenant-a",
                args[2],
                args[3]);
    try {
      new OperatorTransactions(source, true)
          .required(
              () -> {
                var connection = DataSourceUtils.getConnection(source);
                try {
                  require(connection.isReadOnly(), "original JDBC readOnly flag");
                  require(
                      connection.getTransactionIsolation()
                          == java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                      "original RR isolation");
                  require(
                      session.getConnection() == connection,
                      "Mapper uses actual Spring transaction connection");
                  require(
                      resultRecords
                          .invocations("tenant-a", args[2], args[3])
                          .getFirst()
                          .outputDigest()
                          .equals(original),
                      "first snapshot");
                  try (var writers = Executors.newSingleThreadExecutor()) {
                    writers
                        .submit(
                            () ->
                                new JdbcTemplate(source)
                                    .update(
                                        "UPDATE invocation SET output_digest=REPEAT('f',64) WHERE tenant_id=? AND task_id=? AND id=?",
                                        "tenant-a",
                                        args[2],
                                        args[3]))
                        .get(5, TimeUnit.SECONDS);
                  }
                  require(
                      resultRecords
                          .invocations("tenant-a", args[2], args[3])
                          .getFirst()
                          .outputDigest()
                          .equals(original),
                      "second physical query retains RR snapshot after other connection commit");
                  require(
                      resultRecords.tasks("tenant-a", args[2]).size() == 1,
                      "actual task MP projection");
                  try (var statement =
                      connection.prepareStatement(
                          "UPDATE invocation SET output_digest=output_digest WHERE tenant_id=? AND task_id=? AND id=?")) {
                    statement.setString(1, "tenant-a");
                    statement.setString(2, args[2]);
                    statement.setString(3, args[3]);
                    try {
                      statement.executeUpdate();
                      throw new AssertionError("read-only write accepted");
                    } catch (SQLException expected) {
                      System.out.println("READ_ONLY_REFUSAL " + expected.getSQLState());
                    }
                  }
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
                return null;
              });
    } finally {
      new JdbcTemplate(source)
          .update(
              "UPDATE invocation SET output_digest=? WHERE tenant_id=? AND task_id=? AND id=?",
              original,
              "tenant-a",
              args[2],
              args[3]);
    }
    require(
        query.resources().reads() >= 8 && query.resources().bytes() > 0,
        "actual MP/XML result meter");
    var expired =
        new MapperScopeBindingRecords(
            session, new SessionQueryContext(System.nanoTime() - 1, new QueryResources(0, 0, 0)));
    try {
      expired.serverUuid();
      throw new AssertionError("expired context accepted");
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (cause.getCause() != null) cause = cause.getCause();
      require(
          cause instanceof TaskFailure f && f.code().equals("SESSION_QUERY_TIMEOUT"),
          "absolute deadline reaches Mapper");
    }
    System.out.println(
        "QUERY "
            + Payloads.json(
                Map.of(
                    "reads",
                    query.resources().reads(),
                    "bytes",
                    query.resources().bytes(),
                    "operations",
                    query.resources().operations(),
                    "sameConnection",
                    true,
                    "readOnlyRR",
                    true,
                    "snapshotStable",
                    true,
                    "absoluteDeadline",
                    true)));
    System.out.println("PASS operator query and readonly transaction");
  }
}
