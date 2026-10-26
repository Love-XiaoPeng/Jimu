package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactErrorCode;
import io.github.lovexiaopeng.jimu.kernel.artifact.api.ReportLimitsV1;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportPublications;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ReportPublicationService;
import io.github.lovexiaopeng.jimu.kernel.budget.api.BudgetErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.util.ArrayList;
import java.util.List;

/** 暂存与发布的完整资格、首错和事务内顺序；共享 Composer/codec 保持原操作。 */
public final class ReportPublicationServiceImpl implements ReportPublicationService {
  private final ReportPublications records;

  public ReportPublicationServiceImpl(ReportPublications records) {
    this.records = records;
  }

  private void requirePresent(Lease lease, ReportPublications.Task task, String invocationId) {
    if (!task.publicReport()) throw new TaskFailure(409, ArtifactErrorCode.REPORT_POLICY_REQUIRED);
    var invocation = records.invocation(lease, invocationId);
    if (!ToolCapability.PRESENT.equals(invocation.tool())
        || !InvocationState.RUNNING.equals(invocation.state()))
      throw new TaskFailure(409, InvocationErrorCode.INVOCATION_STATE);
  }

  @Override
  public List<Upload> stage(Lease lease, String invocationId, List<ReportFile> files) {
    return records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          requirePresent(lease, task, invocationId);
          if (files.isEmpty()
              || files.size() > 4
              || files.stream().map(ReportFile::path).distinct().count() != files.size())
            throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
          var input = records.read(records.input(lease, invocationId));
          var paths = new ArrayList<String>();
          input.path("files").forEach(file -> paths.add(file.path("path").text()));
          if (!paths.equals(files.stream().map(ReportFile::path).toList()))
            throw new TaskFailure(409, InvocationErrorCode.INVOCATION_MISMATCH);
          var result = new ArrayList<Upload>();
          for (var file : files) {
            requireComposition(task, file);
            if (file.version() < 1
                || file.size() < 1
                || file.size() > ReportLimitsV1.REPORT_BYTES
                || !file.sha256().matches("[a-f0-9]{64}")
                || !"text/markdown; charset=utf-8".equals(file.mediaType()))
              throw new TaskFailure(400, ArtifactErrorCode.REPORT_INVALID);
            String receipt = records.json(file), digest = records.hash(receipt);
            var old = records.matching(lease, invocationId, digest);
            if (!old.isEmpty()) {
              var a = old.getFirst();
              if (!receipt.equals(a.receipt()))
                throw new TaskFailure(409, ArtifactErrorCode.ARTIFACT_MISMATCH);
              result.add(
                  new Upload(
                      a.id(),
                      a.uploadId(),
                      a.state(),
                      file.sha256(),
                      file.size(),
                      a.snapshotKey()));
              continue;
            }
            if (!records.otherProducer(lease, invocationId).isEmpty())
              throw new TaskFailure(409, ArtifactErrorCode.REPORT_ALREADY_STAGED);
            var budget = records.bucket(lease);
            if (budget.held() + file.size() > budget.limit())
              throw new TaskFailure(429, BudgetErrorCode.STORAGE_BUDGET_EXCEEDED);
            String artifact = records.newId(), upload = records.newId();
            records.insert(lease, artifact, invocationId, upload, file, receipt, digest);
            records.hold(lease, file.size());
            records.reserve(lease, artifact, file.size());
            task.event("staging:" + artifact, "artifact.staging");
            result.add(new Upload(artifact, upload, "STAGING", file.sha256(), file.size(), null));
          }
          if (records.stagedIds(lease, invocationId).size() != files.size())
            throw new TaskFailure(409, ArtifactErrorCode.ARTIFACT_MISMATCH);
          return List.copyOf(result);
        });
  }

  private void requireComposition(ReportPublications.Task task, ReportFile file) {
    var pin = task.compositionPin();
    if (pin == null) return;
    var confirmed = task.confirmed();
    for (var row : confirmed) {
      String input = row.input(), output = row.output();
      if (!records.hash(input).equals(row.inputDigest())
          || output == null
          || !records.hash(output).equals(row.outputDigest())
          || !pin.version().equals(row.version())
          || !pin.digest().equals(row.digest()))
        throw new TaskFailure(409, ArtifactErrorCode.REPORT_COMPOSITION_INVALID);
      if (pin.version().equals("0.2.0")) {
        if (task.imageMatches(input, output, file, row)) return;
      } else if (task.textMatches(input, output, file)) return;
    }
    throw new TaskFailure(409, ArtifactErrorCode.REPORT_COMPOSITION_REQUIRED);
  }

  @Override
  public void succeed(
      Lease lease, String invocationId, List<Publication> publications, String output) {
    records.transaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          requirePresent(lease, task, invocationId);
          var artifacts = records.artifacts(lease, invocationId);
          if (artifacts.size() != publications.size()
              || artifacts.isEmpty()
              || publications.stream().map(Publication::artifactId).distinct().count()
                  != publications.size())
            throw new TaskFailure(409, ArtifactErrorCode.ARTIFACT_MISMATCH);
          var result = records.read(output).path("files");
          if (!result.array() || result.size() != publications.size())
            throw new TaskFailure(409, ToolErrorCode.TOOL_OUTPUT_INVALID);
          for (int index = 0; index < publications.size(); index++) {
            var pub = publications.get(index);
            var a =
                artifacts.stream()
                    .filter(row -> pub.artifactId().equals(row.id()))
                    .findFirst()
                    .orElseThrow(() -> new TaskFailure(409, ArtifactErrorCode.ARTIFACT_MISMATCH));
            var receipt = records.receipt(a.receipt());
            var item = result.get(index);
            if (!"STAGING".equals(a.state())
                || !pub.key().equals(a.uploadId() + "-" + a.hash())
                || !records.hash(a.receipt()).equals(a.receiptDigest())
                || !pub.artifactId().equals(item.path("artifactId").text())
                || !receipt.path().equals(item.path("path").text())
                || receipt.version() != item.path("version").number()
                || !receipt.sha256().equals(item.path("sha256").text())
                || receipt.size() != item.path("size").number()
                || !receipt.mediaType().equals(item.path("mediaType").text()))
              throw new TaskFailure(409, ArtifactErrorCode.ARTIFACT_MISMATCH);
            records.publish(lease, pub.key(), pub.artifactId());
            records.settleStorage(lease, pub.artifactId());
            task.event("published:" + pub.artifactId(), "artifact.created");
          }
          records.succeed(lease, output, records.hash(output), invocationId);
          records.settleTool(lease, invocationId);
          task.event("result:" + invocationId, "tool.succeeded");
          return null;
        });
  }
}
