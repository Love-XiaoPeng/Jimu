package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionRetentions;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionRetentions.Body;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionRetentions.RetainedBody;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionRetentionService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 保留原拒绝顺序与短事务；所有正文、摘要、占用和事件一起提交或回滚。 */
public final class QuestionRetentionServiceImpl implements QuestionRetentionService {
  private final QuestionRetentions records;
  private final LedgerUnitOfWork work;
  private final SnapshotDigest digest;

  public QuestionRetentionServiceImpl(
      QuestionRetentions records, LedgerUnitOfWork work, SnapshotDigest digest) {
    this.records = records;
    this.work = work;
    this.digest = digest;
  }

  private void retain(List<RetainedBody> retained, String prefix, List<Body> bodies) {
    for (var body : bodies)
      if (body.value() != null)
        retained.add(
            new RetainedBody(
                prefix + body.field(),
                digest.hash(body.value()),
                body.value().getBytes(StandardCharsets.UTF_8).length));
  }

  @Override
  public void cleanup() {
    for (var candidate : records.candidates()) {
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            var task = records.lockTask(candidate);
            if (!"RETAINED".equals(task.cleanupState())
                || !task.view().terminal()
                || !task.writersQuiescent()) return null;
            if (task.unsafeReservations() || task.unknownInvocations()) return null;
            var retained = new ArrayList<RetainedBody>();
            retain(retained, "task:", task.bodies());
            String tombstone = "{\"version\":1,\"state\":\"QUESTION_PURGED\"}";
            for (var step : task.steps()) {
              retain(retained, "step:" + step.id() + ":", step.bodies());
              task.purgeStep(step.id(), tombstone);
            }
            for (var invocation : task.invocations()) {
              retain(retained, "invocation:" + invocation.id() + ":", invocation.bodies());
              task.purgeInvocation(invocation.id(), tombstone);
            }
            for (var question : task.questions()) {
              var metadata = new ArrayList<RetainedBody>();
              retain(metadata, "", question.bodies());
              task.purgeQuestion(question.id(), tombstone, records.metadata(metadata));
            }
            task.purgeTask(tombstone, records.metadata(retained));
            if (task.releaseHold() == 1) task.releaseBudget();
            task.event();
            return null;
          });
    }
  }
}
