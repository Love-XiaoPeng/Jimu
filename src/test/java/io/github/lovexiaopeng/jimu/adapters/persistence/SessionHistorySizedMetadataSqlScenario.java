package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 新投影的私库编码与写前拒绝；仅复用合法受理行，表示边界变更全部回滚，不冒充raw业务证明。 */
final class SessionHistorySizedMetadataSqlScenario {
  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
    System.out.println("PASS " + message);
    System.out.flush();
  }

  private static JdbcLedger view(JdbcLedger root) throws Exception {
    var factory = JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
    factory.setAccessible(true);
    var v =
        (JdbcLedger)
            factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    v.queryContext().restrictLineage();
    return v;
  }

  static void verify(JdbcLedger root, JdbcTemplate db, String task) throws Exception {
    // These intentionally non-contract binding values exercise metadata representation only.
    // They are never accepted as a raw history proof or left in the genuine business source.
    for (String raw : List.of("", "汉😀\u0000  ", "x".repeat(65535))) {
      var before = SessionDependencyScenario.snapshot(db);
      var v = view(root);
      var q = v.queryContext();
      var rollback = new IllegalStateException("metadata-representation-rollback");
      try {
        v.sessionTransaction(
            () -> {
              var locked = v.locked("tenant-a", task);
              db.update(
                  "UPDATE task_session_history SET binding_snapshot=?,next_seq=next_seq+1 WHERE tenant_id='tenant-a' AND task_id=?",
                  raw,
                  task);
              var records = new MapperSessionMetadataAccounting(v.sessions, locked);
              long sequence;
              try (var batch = records.batch(Table.TASK_SESSION_HISTORY)) {
                var row = batch.iterator().next();
                check(
                    batch.scalarBytes(row.value("binding_snapshot"))
                        == raw.getBytes(StandardCharsets.UTF_8).length,
                    "real new metadata SQL UTF8 length utf16=" + raw.length());
                check(batch.scalarBytes(27L) == 2, "ordinary Long27 remains decimal metadata");
                sequence = (Long) row.value("next_seq");
              }
              db.update(
                  "UPDATE task_session_history SET next_seq=next_seq+1 WHERE tenant_id='tenant-a' AND task_id=?",
                  task);
              try (var batch = records.batch(Table.TASK_SESSION_HISTORY)) {
                check(
                    (Long) batch.iterator().next().value("next_seq") == sequence + 1,
                    "real second metadata SELECT reads fresh next_seq");
              }
              throw rollback;
            },
            q.deadlineNanos());
        throw new AssertionError("representation probe must roll back");
      } catch (IllegalStateException expected) {
        check(expected == rollback, "representation rollback keeps first exception");
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)),
          "metadata representation exact22-table rollback");
      check(q.scratchLive() == 65536, "metadata representation owners released");
      SessionHistoryObservation.snapshot(db, "metadata-representation-" + raw.length());
    }
    var before = SessionDependencyScenario.snapshot(db);
    var v = view(root);
    var q = v.queryContext();
    RuntimeException[] first = {null};
    try {
      v.sessionTransaction(
          () -> {
            var records =
                new MapperSessionMetadataAccounting(v.sessions, v.locked("tenant-a", task));
            long original =
                db.queryForObject(
                    "SELECT metadata_bytes FROM task_session_history WHERE tenant_id='tenant-a' AND task_id=?",
                    Long.class,
                    task);
            records.account(original + 1);
            Object block = new Object();
            q.retainScratch(block, 1048576 - q.scratchLive() - 1);
            try {
              try (var batch = records.batch(Table.TASK_SESSION_HISTORY)) {
                throw new AssertionError("must reject before provider allocation");
              } catch (TaskFailure rejected) {
                check(
                    rejected.code().equals("SESSION_HISTORY_LIMIT"),
                    "actual bootstrap reserve refusal");
                first[0] = rejected;
              }
            } finally {
              q.releaseScratch(block);
            }
            return null;
          },
          q.deadlineNanos());
      throw new AssertionError("caught refusal must prevent commit");
    } catch (RuntimeException expected) {
      check(
          expected == first[0] && expected == q.recordedFailure(),
          "caught reserve refusal retains first slot through Work.finish");
    }
    check(
        before.equals(SessionDependencyScenario.snapshot(db)),
        "written metadata then caught reserve exact22-table rollback");
    check(q.scratchLive() == 65536, "failed bootstrap owners released");
    check(
        q.statements().keySet().stream()
            .noneMatch(k -> k.endsWith(".metadataTaskSessionHistorySized")),
        "reserve refusal happens before new SELECT");
    SessionHistoryObservation.snapshot(db, "metadata-reserve-rejected");
  }
}
