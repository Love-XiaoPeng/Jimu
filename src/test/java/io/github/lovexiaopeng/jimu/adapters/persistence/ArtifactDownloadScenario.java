package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 准确旧新制品共用驱动；实际Mapper结果实计、缓存和绝对截止的定向证据。 */
public final class ArtifactDownloadScenario {
  public static void main(String[] args) throws Exception {
    var props = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      props.load(input);
    }
    var env = new StandardEnvironment();
    env.getPropertySources().addFirst(new PropertiesPropertySource("private-download", props));
    var source =
        new DriverManagerDataSource(
            props.getProperty("jimu.db.url"),
            props.getProperty("jimu.db.user"),
            props.getProperty("jimu.db.password"));
    var ledger = new JdbcLedger(source, new ModelProfiles(env));
    var owner = new Tasks.Identity("tenant-a", "alice");
    var method = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    method.setAccessible(true);
    var scoped =
        (JdbcLedger)
            method.invoke(ledger, System.nanoTime() + 10_000_000_000L, new QueryResources(0, 0, 0));
    var first = scoped.download(owner, args[1]);
    var once = scoped.queryContext().resources();
    if (once.reads() == 0 || once.bytes() == 0 || once.operations() == 0)
      throw new AssertionError("physical meter empty");
    if (!first.equals(scoped.download(owner, args[1])))
      throw new AssertionError("changed metadata");
    var twice = scoped.queryContext().resources();
    if (twice.reads() != 2 * once.reads()
        || twice.bytes() != 2 * once.bytes()
        || twice.operations() != 2 * once.operations())
      throw new AssertionError("cache or double meter");
    System.out.println("QUERY " + Payloads.json(once));
    var expired =
        (JdbcLedger) method.invoke(ledger, System.nanoTime() - 1, new QueryResources(0, 0, 0));
    try {
      expired.download(owner, args[1]);
      throw new AssertionError("deadline accepted");
    } catch (RuntimeException failure) {
      Throwable cause = failure;
      while (cause.getCause() != null && !(cause instanceof TaskFailure)) cause = cause.getCause();
      if (!(cause instanceof TaskFailure code) || !code.code().equals("SESSION_QUERY_TIMEOUT"))
        throw failure;
    }
    System.out.println("PASS artifact metering/deadline");
    if (args.length > 2) {
      var barrier = Path.of(args[2]);
      try (var connection = source.getConnection();
          var pool = Executors.newSingleThreadExecutor()) {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          try (var id = statement.executeQuery("SELECT CONNECTION_ID()")) {
            id.next();
            System.out.println("BLOCKER_CONNECTION " + id.getLong(1));
          }
          statement
              .executeQuery(
                  "SELECT enabled FROM actor_access WHERE tenant_id='tenant-a' AND actor_id='alice' FOR UPDATE")
              .close();
          var future = pool.submit(() -> ledger.download(owner, args[1]));
          Files.writeString(
              barrier.resolve("waiting"), "actor row exclusive lock held; download queued");
          long end = System.nanoTime() + 8_000_000_000L;
          while (!Files.exists(barrier.resolve("release")) && System.nanoTime() < end)
            Thread.sleep(20);
          if (!Files.exists(barrier.resolve("release")))
            throw new AssertionError("bounded barrier expired");
          if (future.isDone()) throw new AssertionError("download bypassed current actor lock");
          connection.rollback();
          if (!first.equals(future.get(10, TimeUnit.SECONDS)))
            throw new AssertionError("lock release changed metadata");
          System.out.println("PASS two-connection actor lock wait then authorized completion");
        } finally {
          connection.rollback();
        }
      }
    }
  }
}
