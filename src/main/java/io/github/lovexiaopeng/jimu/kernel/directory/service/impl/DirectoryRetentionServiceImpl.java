package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryStorageLimit;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.Body;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.LockedTask;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.RetainedBody;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRetentions.Workspace;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryRetentionService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 三段短事务与事务外文件清单/删除；保留generation、receipt和原异常传播以便重试。 */
public final class DirectoryRetentionServiceImpl implements DirectoryRetentionService {
  private final DirectoryRetentions records;
  private final Workspace workspace;
  private final LedgerUnitOfWork work;
  private final SnapshotDigest digest;

  public DirectoryRetentionServiceImpl(
      DirectoryRetentions records,
      Workspace workspace,
      LedgerUnitOfWork work,
      SnapshotDigest digest) {
    this.records = records;
    this.workspace = workspace;
    this.work = work;
    this.digest = digest;
  }

  private static boolean safe(LockedTask task) {
    return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.state())
        && task.writersQuiescent()
        && !task.unsafeReservations()
        && !task.activeInvocations()
        && !task.dispatchedModels();
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

  private static void storage(LockedTask task, String additional) {
    if (task.directoryCapability())
      DirectoryStorageLimit.check(
          task.storageBytes(), additional.getBytes(StandardCharsets.UTF_8).length);
  }

  @Override
  public void cleanup() {
    if (!workspace.available()) return;
    for (var candidate : records.candidates()) {
      var purge =
          work.required(
              () -> {
                records.lockBudget(candidate.tenant());
                var task = records.lockTask(candidate);
                var projection = task.projection();
                if ("PURGED".equals(projection.state())
                    || projection.retainUntil().isAfter(records.now())
                    || !safe(task)) return null;
                if ("PURGING".equals(projection.state())) return projection;
                var retained = new ArrayList<RetainedBody>();
                retain(retained, "task:", task.bodies());
                String tombstone = "{\"version\":1,\"state\":\"DIRECTORY_PURGED\"}";
                for (var step : task.steps()) {
                  retain(retained, "step:" + step.id() + ":", step.bodies());
                  task.purgeStep(step.id(), tombstone);
                }
                for (var invocation : task.invocations()) {
                  retain(retained, "invocation:" + invocation.id() + ":", invocation.bodies());
                  task.purgeInvocation(invocation.id(), tombstone);
                }
                for (var path : task.paths()) {
                  retain(retained, "path:" + path.id() + ":", path.bodies());
                  task.purgePath(path.id());
                }
                for (var dispatch : task.dispatches()) {
                  retain(retained, "dispatch:" + dispatch.id() + ":", dispatch.bodies());
                  task.purgeDispatch(dispatch.id(), tombstone);
                }
                retain(retained, "directory:", List.of(new Body("cwd", projection.cwd())));
                String metadata = records.metadata(retained);
                storage(task, metadata);
                task.purgeTask(tombstone);
                task.markPurging(metadata);
                return task.projection();
              });
      if (purge == null) continue;
      String generation = purge.generation();
      String receipt = purge.receipt();
      if (receipt == null) {
        String planned = workspace.plan(candidate);
        receipt =
            work.required(
                () -> {
                  records.lockBudget(candidate.tenant());
                  var task = records.lockTask(candidate);
                  var projection = task.projection();
                  if (!"PURGING".equals(projection.state())
                      || !generation.equals(projection.generation())
                      || !safe(task)) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
                  if (projection.receipt() != null) return projection.receipt();
                  storage(task, planned);
                  task.storeReceipt(planned);
                  return planned;
                });
      }
      workspace.purge(candidate, receipt);
      String confirmed = receipt;
      work.required(
          () -> {
            records.lockBudget(candidate.tenant());
            var task = records.lockTask(candidate);
            var projection = task.projection();
            if ("PURGED".equals(projection.state())) return null;
            if (!"PURGING".equals(projection.state())
                || !generation.equals(projection.generation())
                || !confirmed.equals(projection.receipt())
                || !safe(task)) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            task.markPurged();
            task.event();
            if (task.releaseHold() != 1) throw new TaskFailure(409, "RESERVATION_STATE");
            task.releaseBudget();
            return null;
          });
    }
  }
}
