package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryPersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 保留原目录资格、路径冻结、证据结算与成功重放次序。 */
public final class DirectoryPersistenceServiceImpl implements DirectoryPersistenceService {
  private final DirectoryRecords records;

  public DirectoryPersistenceServiceImpl(DirectoryRecords records) {
    this.records = records;
  }

  public Row directoryProjection(Row task) {
    var p = records.projectionRow(task.tenantId(), task.id());
    if (p.recordVersion() != 1 || p.directoryVersion() != 1 || p.pathPolicyVersion() != 1)
      throw new TaskFailure(409, "DIRECTORY_BINDING_UNSUPPORTED");
    String namespace = records.hash(task.tenantId() + ":" + task.id());
    if (!namespace.equals(p.namespaceDigest())
        || !records.hash(p.bindingSnapshot()).equals(p.bindingDigest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    records.binding(p.bindingSnapshot(), namespace);
    if (p.revision() == 0
        && (p.sourceInvocationIdObject() != null
            || "PRESENT".equals(p.contentState()) && !"/".equals(p.cwd())))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return p;
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot
      directorySnapshot(Lease lease) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          if (!records.directoryTask(task)) throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
          var p = directoryProjection(task);
          return new io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot(
              p.cwd(), p.revision(), p.bindingDigest(), "READY".equals(p.workspaceState()));
        });
  }

  @Override
  public void directoryReady(Lease lease) {
    records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var p = directoryProjection(task);
          if (!"UNINITIALIZED".equals(p.workspaceState()))
            throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
          records.readyWorkspace(lease.tenant(), lease.taskId());
          records.todoCommitFence(lease);
          return null;
        });
  }

  public Row originalDirectorySlot(
      Lease lease, String stepId, Call call, String version, String digest) {
    var i =
        records.originalDirectoryInvocation(lease.tenant(), lease.taskId(), stepId, call.slot());
    var task = records.originalDirectoryTask(lease.tenant(), lease.taskId());
    var pin = records.tools(task).pins().get(call.tool());
    if (pin == null
        || !pin.version().equals(version)
        || !pin.digest().equals(digest)
        || pin.installationEpoch() != i.installationEpoch())
      throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    if (!lease.taskId().equals(i.stepTask())
        || !"RESPONDED".equals(i.stepState())
        || i.recordVersion() != 1
        || i.stepVersion() != 1
        || !records.hash(i.responseSnapshot()).equals(i.responseDigest())
        || !call.input().equals(i.inputSnapshot())
        || !records.hash(call.input()).equals(i.inputDigest())
        || !call.providerId().equals(i.providerId())
        || !call.tool().equals(i.tool())
        || !version.equals(i.pluginVersion())
        || !digest.equals(i.pluginDigest())) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var response = records.response(i.responseSnapshot());
    if (response.version() != 2) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    int index = -1;
    for (int slot = 0; slot < response.calls().size(); slot++) {
      var wire = response.calls().get(slot);
      if (new Call(slot + ":" + wire.id(), wire.id(), wire.name(), wire.arguments()).equals(call))
        index = slot;
    }
    if (index < 0) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    for (int slot = 0; slot < index; slot++) {
      var wire = response.calls().get(slot);
      var prior =
          records.priorDirectorySlot(
              lease.tenant(), lease.taskId(), stepId, slot + ":" + wire.id());
      if (!"SUCCEEDED".equals(prior.state())) throw new TaskFailure(409, "DIRECTORY_SLOT_ORDER");
    }
    if (!records.unfinishedDirectoryStep(lease.tenant(), lease.taskId(), i.stepOrdinal()).isEmpty())
      throw new TaskFailure(409, "DIRECTORY_SLOT_ORDER");
    i.directorySlotIndex(index);
    return i;
  }

  public Row freezeDirectorySlot(Row task, Row i) {
    var p = directoryProjection(task);
    if (!"READY".equals(p.workspaceState())) throw new TaskFailure(409, "FS_BINDING_UNPROVEN");
    var existing = records.existingDirectoryBinding(task.tenantId(), i.id());
    if (!existing.isEmpty()
        && (existing.getFirst().recordVersion() != 1
            || existing.getFirst().pathPolicyVersion() != 1))
      throw new TaskFailure(409, "DIRECTORY_BINDING_UNSUPPORTED");
    String base = existing.isEmpty() ? p.cwd() : existing.getFirst().baseCwd();
    long revision = existing.isEmpty() ? p.revision() : existing.getFirst().baseRevision();
    String mode, target = null;
    List<String> paths = List.of();
    if ("working_directory".equals(i.tool())) {
      var input = records.input(i.inputSnapshot());
      mode = input.cd() == null ? "READ" : "SET";
      target = input.cd() == null ? base : records.resolve(base, input.cd(), true);
    } else {
      mode = "FILES";
      var input = records.inputJson(i.inputSnapshot(), 65535);
      records.validateFile(i.tool(), input);
      paths = List.of(records.resolve(base, input.path("file_path").textValue(), false));
    }
    String json = records.json(paths);
    if (existing.isEmpty()) {
      records.directoryStorage(
          task,
          base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              + (target == null
                  ? 0
                  : target.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
              + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      records.insertDirectoryBinding(
          task.tenantId(),
          i.id(),
          task.id(),
          i.stepId(),
          p.bindingDigest(),
          p.namespaceDigest(),
          i.directorySlotIndex(),
          mode,
          revision,
          base,
          target,
          json,
          records.hash(json),
          i.inputDigest());
    }
    var frozen = records.frozenDirectoryBinding(task.tenantId(), i.id());
    if (!"PRESENT".equals(frozen.contentState())
        || !p.bindingDigest().equals(frozen.bindingDigest())
        || !p.namespaceDigest().equals(frozen.namespaceDigest())
        || !i.stepId().equals(frozen.stepId())
        || !task.id().equals(frozen.taskId())
        || i.directorySlotIndex() != frozen.slotIndex()
        || !mode.equals(frozen.mode())
        || !java.util.Objects.equals(target, frozen.targetCwd())
        || !json.equals(frozen.canonicalPathsSnapshot())
        || !records.hash(json).equals(frozen.canonicalPathsDigest())
        || !i.inputDigest().equals(frozen.inputDigest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!"SUCCEEDED".equals(i.state()) && (revision != p.revision() || !base.equals(p.cwd())))
      throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
    return frozen;
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen freezeDirectory(
      Lease lease, String stepId, Call call, String version, String digest) {
    if (!"working_directory".equals(call.tool()))
      throw new TaskFailure(409, "DIRECTORY_COMMIT_REQUIRED");
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          if (!records.directoryTask(task)) throw new TaskFailure(403, "DIRECTORY_NOT_AUTHORIZED");
          var i = originalDirectorySlot(lease, stepId, call, version, digest);
          var f = freezeDirectorySlot(task, i);
          String output = null;
          if ("SUCCEEDED".equals(i.state())) {
            directoryResultSource(task, i, f);
            output = i.outputSnapshot();
          } else if (!"PENDING".equals(i.state()))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          records.todoCommitFence(lease);
          return new io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen(
              i.id(), f.mode(), f.baseCwd(), f.baseRevision(), f.targetCwd(), List.of(), output);
        });
  }

  public void directoryResultSource(Row task, Row i, Row f) {
    if (!"SUCCEEDED".equals(i.state())
        || !"working_directory".equals(i.tool())
        || f.appliedRevisionObject() == null
        || f.committedAttemptIdObject() == null
        || !records.hash(i.outputSnapshot()).equals(i.outputDigest())
        || !records.output(i.outputSnapshot()).cwd().equals(f.targetCwd()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    boolean changed = Boolean.TRUE.equals(f.changedObject());
    if (changed != !f.baseCwd().equals(f.targetCwd())
        || changed && !"SET".equals(f.mode())
        || f.appliedRevision() != f.baseRevision() + (changed ? 1 : 0)
        || changed != (f.changedEventSeqObject() != null)
        || records
                .directorySourceAttempt(
                    task.tenantId(), task.id(), f.committedAttemptId(), f.committedFence())
                .size()
            != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (changed
        && records
                .directorySourceEvent(
                    task.tenantId(), task.id(), f.changedEventSeq(), "directory-change:" + i.id())
                .size()
            != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  @Override
  public io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result commitDirectory(
      Lease lease,
      String invocationId,
      io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Evidence evidence) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var p = directoryProjection(task);
          var original =
              records.directoryCommitInvocation(lease.tenant(), lease.taskId(), invocationId);
          var i =
              originalDirectorySlot(
                  lease,
                  original.stepId(),
                  new Call(
                      original.slot(),
                      original.providerId(),
                      original.tool(),
                      original.inputSnapshot()),
                  original.pluginVersion(),
                  original.pluginDigest());
          var f = freezeDirectorySlot(task, i);
          if ("SUCCEEDED".equals(i.state())) {
            directoryResultSource(task, i, f);
            return records.output(i.outputSnapshot());
          }
          if (!"PENDING".equals(i.state())
              || !"working_directory".equals(i.tool())
              || f.appliedRevisionObject() != null)
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (!evidence.namespace().equals(p.namespaceDigest())
              || !evidence.target().equals(f.targetCwd())
              || !evidence.filesDigest().matches("[a-f0-9]{64}"))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          var budget = records.bucket(lease.tenant());
          if (task.toolsUsed() >= task.maxTools() || budget.toolUsed() >= budget.toolLimit())
            throw new TaskFailure(429, "TOOL_BUDGET_EXCEEDED");
          boolean changed = !f.baseCwd().equals(f.targetCwd());
          long revision = f.baseRevision() + (changed ? 1 : 0);
          String output =
              records.json(
                  new io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result(
                      f.targetCwd()));
          records.output(output);
          String proof = records.json(evidence);
          records.directoryStorage(
              task,
              output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + proof.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
          records.countDirectoryTool(lease.tenant(), lease.taskId());
          records.countDirectoryBudget(lease.tenant());
          records.reserve(
              lease.tenant(),
              lease.taskId(),
              invocationId,
              invocationId,
              "TOOL",
              1,
              "SETTLED",
              "KNOWN");
          records.settleDirectoryReservation(lease.tenant(), invocationId);
          records.event(task, "intent:" + invocationId, "tool.dispatched", null);
          Long seq = null;
          if (changed) {
            records.event(
                task, "directory-change:" + invocationId, "working_directory.changed", null);
            seq =
                records.factSequence(
                    lease.tenant(), lease.taskId(), "directory-change:" + invocationId);
          }
          if (records.completeDirectoryInvocation(
                  output, records.hash(output), lease.tenant(), invocationId)
              != 1) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (records.completeDirectoryBinding(
                  revision,
                  changed,
                  lease.attemptId(),
                  lease.fence(),
                  seq,
                  proof,
                  lease.tenant(),
                  invocationId)
              != 1) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          if (changed
              && records.advanceDirectoryRevision(
                      f.targetCwd(),
                      revision,
                      invocationId,
                      lease.tenant(),
                      lease.taskId(),
                      f.baseRevision())
                  != 1) throw new TaskFailure(409, "DIRECTORY_REVISION_CONFLICT");
          records.event(task, "result:" + invocationId, "tool.completed", null);
          records.todoCommitFence(lease);
          return records.output(output);
        });
  }

  @Override
  public String directoryFilePath(Lease lease, String invocationId) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var i = records.directoryFileInvocation(lease.tenant(), lease.taskId(), invocationId);
          i =
              originalDirectorySlot(
                  lease,
                  i.stepId(),
                  new Call(i.slot(), i.providerId(), i.tool(), i.inputSnapshot()),
                  i.pluginVersion(),
                  i.pluginDigest());
          var f = freezeDirectorySlot(task, i);
          if (!"FILES".equals(f.mode()) || !"RUNNING".equals(i.state()))
            throw new TaskFailure(409, "INVOCATION_MISMATCH");
          return records.readJson(f.canonicalPathsSnapshot()).get(0).textValue();
        });
  }

  @Override
  public Map<String, Object> getWorkingDirectory(Identity identity, String taskId) {
    try {
      UUID.fromString(taskId);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, "NOT_FOUND");
    }
    return records.transaction(
        () -> {
          var task = records.visible(identity, taskId);
          records.access(task);
          var r = records.directoryOwnerView(identity.tenant(), taskId, identity.actor());
          boolean absent = r.taskIdObject() == null;
          var view = new java.util.LinkedHashMap<String, Object>();
          view.put("version", 1);
          view.put("taskId", taskId);
          view.put("asOfSeq", r.nextEventSeq());
          view.put("revision", absent ? 0 : r.revision());
          view.put("state", absent ? "ABSENT" : r.contentState());
          view.put("cwd", absent ? null : r.cwd());
          view.put("source", null);
          view.put("retainUntil", absent ? null : r.retainUntil().toString());
          if (!absent && r.revision() > 0) {
            if (!taskId.equals(r.invocationTask())
                || !"SUCCEEDED".equals(r.invocationState())
                || !"working_directory".equals(r.tool())
                || !Boolean.TRUE.equals(r.changedObject())
                || r.revision() != r.appliedRevision())
              throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            if ("PRESENT".equals(r.contentState())) {
              if (!records.hash(r.outputSnapshot()).equals(r.outputDigest())
                  || !records.output(r.outputSnapshot()).cwd().equals(r.cwd())
                  || !r.cwd().equals(r.targetCwd())) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            } else {
              var retained = records.readJson(r.retainedDigests());
              if (!retained
                  .path("invocation:" + r.invocationId() + ":output_snapshot")
                  .path("sha256")
                  .asText()
                  .equals(r.outputDigest())) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            }
            if (records
                        .directoryViewEvent(
                            identity.tenant(),
                            taskId,
                            r.changedEventSeq(),
                            "directory-change:" + r.invocationId())
                        .size()
                    != 1
                || records
                        .directoryViewAttempt(
                            identity.tenant(), taskId, r.committedAttemptId(), r.committedFence())
                        .size()
                    != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
            view.put(
                "source",
                Map.of(
                    "invocationId",
                    r.invocationId(),
                    "checkpointId",
                    r.stepId(),
                    "providerCallId",
                    r.providerId(),
                    "createdAttemptId",
                    r.attemptId(),
                    "committedAttemptId",
                    r.committedAttemptId(),
                    "fence",
                    r.committedFence(),
                    "eventId",
                    taskId + ":" + r.changedEventSeq(),
                    "outputSha256",
                    r.outputDigest(),
                    "updatedAt",
                    r.committedAt().toString()));
          }
          records.view(view);
          return view;
        });
  }
}
