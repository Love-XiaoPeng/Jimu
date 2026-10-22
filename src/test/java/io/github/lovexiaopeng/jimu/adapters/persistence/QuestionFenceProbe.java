package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Foreground fault driver uses the old real lease; never bypasses the production fence. */
public final class QuestionFenceProbe {
  private QuestionFenceProbe() {}

  public static void main(String[] args) throws Exception {
    Properties values = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      values.load(input);
    }
    var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new PropertiesPropertySource("fixture", values));
    var ledger =
        new JdbcLedger(
            new DriverManagerDataSource(
                values.getProperty("jimu.db.url"),
                values.getProperty("jimu.db.user"),
                values.getProperty("jimu.db.password")),
            new ModelProfiles(environment));
    ledger.questionsEnabled(true);
    var lease =
        new ExecutionLedger.Lease(
            args[1],
            args[2],
            args[3],
            args[4],
            Long.parseLong(args[5]),
            args[6],
            Instant.now().plusSeconds(180),
            "synthetic old lease",
            List.of("ask_user_question"),
            1);
    int rejected = 0;
    List<Runnable> mutations =
        List.of(
            () -> ledger.checkpoint(lease),
            () -> ledger.prepare(lease, "{}"),
            () -> ledger.finish(lease, "forged"),
            () -> ledger.stop(lease, "forged"),
            () -> ledger.toolSucceeded(lease, "missing", "{}"),
            () ->
                ledger.openOrResult(
                    lease,
                    "missing",
                    new ExecutionLedger.Call("missing", "missing", "ask_user_question", "{}"),
                    "0.1.0",
                    "missing"));
    for (var mutation : mutations) {
      try {
        mutation.run();
        throw new AssertionError("Stale lease wrote a fact");
      } catch (TaskFailure expected) {
        if (!expected.code().equals("FENCE_REJECTED")) throw expected;
        rejected++;
      }
    }
    if (ledger.renew(lease)) throw new AssertionError("Stale lease renewed");
    System.out.println("PASS old fence rejects " + rejected + " operations and heartbeat");
  }
}
