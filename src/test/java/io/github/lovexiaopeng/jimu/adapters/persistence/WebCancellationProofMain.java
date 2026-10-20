package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Cancellation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.CheckpointCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Private fixture only. Never included in the runtime JAR. */
public final class WebCancellationProofMain {
  private WebCancellationProofMain() {}

  public static void main(String[] args) throws Exception {
    var config = new Properties();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      config.load(input);
    }
    var source =
        new DriverManagerDataSource(
            config.getProperty("jimu.db.url"),
            config.getProperty("jimu.db.user"),
            config.getProperty("jimu.db.password"));
    var db = new JdbcTemplate(source);
    var row =
        db.queryForMap(
            "SELECT i.tenant_id,i.task_id,i.attempt_id,a.worker_id,a.fence FROM invocation i JOIN task_attempt a ON a.tenant_id=i.tenant_id AND a.id=i.attempt_id WHERE i.id=?",
            args[1]);
    var lease =
        new Lease(
            row.get("tenant_id").toString(),
            "alice",
            row.get("task_id").toString(),
            row.get("attempt_id").toString(),
            ((Number) row.get("fence")).longValue() + (args[2].equals("foreign-fence") ? 1 : 0),
            row.get("worker_id").toString(),
            Instant.now().plusSeconds(60),
            "fixture",
            List.of(),
            1);
    var cancellation =
        new Cancellation(
            args[2].equals("first") ? Reason.CHECKPOINT_REJECTED : Reason.CALLER_INTERRUPT,
            Phase.BODY,
            args[2].equals("first") ? 123 : 999,
            CheckpointCode.FENCE_REJECTED,
            7);
    try {
      new JdbcLedger(source, null)
          .observeWebFetch(
              lease, args[1], new WebFetchObservation(Phase.BODY, Failure.CANCELLED, cancellation));
      System.out.println("{\"recorded\":true}");
    } catch (TaskFailure error) {
      System.out.println(Payloads.json(java.util.Map.of("code", error.code())));
      System.exit(2);
    }
  }
}
