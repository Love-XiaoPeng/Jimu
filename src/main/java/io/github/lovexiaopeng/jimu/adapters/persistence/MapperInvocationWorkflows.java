package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Named SQL and module-proof operations, sharing the original transaction and fence. */
final class MapperInvocationWorkflows implements InvocationWorkflowRecords {
  private final JdbcLedger ledger;

  MapperInvocationWorkflows(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public Admission lockAdmission(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest) {
    var task = ledger.fenced(lease, true);
    var budget = ledger.bucket(lease.tenant());
    var initial =
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectOriginalSlot(
                    ledger.queryContext(), lease.tenant(), stepId, call.slot(), lease.taskId()));
    return new Admission() {
      private java.util.Map<String, Object> invocation = initial;

      public Slot slot() {
        return new Slot(
            ledger.s(invocation, "id"),
            ledger.s(invocation, "effect_intent_id"),
            ledger.s(invocation, "tool"),
            ledger.s(invocation, "provider_id"),
            ledger.s(invocation, "input_digest"),
            ledger.s(invocation, "state"),
            ledger.s(invocation, "output_snapshot"),
            ledger.s(invocation, "output_digest"));
      }

      public void freezeDirectory() {
        if (ledger.directoryTask(task)) {
          invocation =
              ledger.originalDirectorySlot(lease, stepId, call, pluginVersion, pluginDigest);
          ledger.freezeDirectorySlot(task, invocation);
        }
      }

      public MaterialUse materialUse() {
        var material = ledger.material(task);
        if (material != null) {
          ledger.initialFile(task);
          var input = Payloads.read(call.input(), com.fasterxml.jackson.databind.JsonNode.class);
          var files = input.path("files");
          return new MaterialUse(
              material.sources().keySet(),
              material.target(),
              input.path("url").asText(),
              input.path("file_path").asText(),
              files.size(),
              call.tool().equals("present") && files.size() == 1
                  ? files.get(0).path("path").asText()
                  : null);
        }
        return null;
      }

      public boolean hasSuccessfulRead() {
        return !ledger
            .mappers
            .invocations()
            .selectSuccessfulRead(ledger.queryContext(), lease.tenant(), lease.taskId())
            .isEmpty();
      }

      public Pin pin() {
        var pinned = ledger.tools(task).pins().get(call.tool());
        var installation =
            JdbcLedger.first(
                ledger.mappers.selectInstallation(
                    ledger.queryContext(), lease.tenant(), call.tool()));
        return new Pin(
            ledger.s(invocation, "plugin_version"),
            ledger.s(invocation, "plugin_digest"),
            ledger.n(invocation, "installation_epoch"),
            pinned.version(),
            pinned.digest(),
            pinned.installationEpoch(),
            ledger.s(installation, "plugin_version"),
            ledger.s(installation, "plugin_digest"));
      }

      public long toolsUsed() {
        return ledger.n(task, "tools_used");
      }

      public long maxTools() {
        return ledger.n(task, "max_tools");
      }

      public long tenantTools() {
        return ledger.n(budget, "tool_used");
      }

      public long tenantToolLimit() {
        return ledger.n(budget, "tool_limit");
      }

      public void reserveUnit() {
        String invocationId = ledger.s(invocation, "id");
        ledger
            .mappers
            .tasks()
            .reserveToolUnit(ledger.queryContext(), lease.tenant(), lease.taskId());
        ledger.mappers.budgets().reserveToolUnit(ledger.queryContext(), lease.tenant());
        ledger.reserve(
            lease.tenant(),
            lease.taskId(),
            invocationId,
            invocationId,
            "TOOL",
            1,
            "RESERVED",
            "KNOWN");
      }

      public void markRunning() {
        ledger
            .mappers
            .invocations()
            .markAdmittedRunning(ledger.queryContext(), lease.tenant(), ledger.s(invocation, "id"));
      }

      public void dispatched() {
        ledger.event(task, "intent:" + ledger.s(invocation, "id"), "tool.dispatched", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task)) ledger.todoCommitFence(lease);
      }
    };
  }

  @Override
  public Success lockSuccess(Lease lease, String invocationId, String output) {
    var first = ledger.fenced(lease, false);
    var task = ledger.directoryTask(first) ? ledger.fenced(lease, true) : first;
    if (ledger.directoryTask(task)) {
      if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
        throw new TaskFailure(429, "DIRECTORY_STORAGE_LIMIT");
      ledger.directoryStorage(
          task, output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    var producer =
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectTaskInvocation(
                    ledger.queryContext(), lease.tenant(), lease.taskId(), invocationId));
    return new Success() {
      private java.util.Map<String, Object> proofStep;

      public String tool() {
        return ledger.s(producer, "tool");
      }

      public ImageProof imageProof() {
        if ("read_image".equals(ledger.s(producer, "tool"))) {
          var committed =
              ledger
                  .mappers
                  .imageObjects()
                  .selectCommittedProducer(
                      ledger.queryContext(), lease.tenant(), lease.taskId(), invocationId);
          var image =
              Payloads.read(output, io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result.class);
          return new ImageProof(
              committed.stream().map(row -> (String) row.get("image_id")).toList(),
              image.image().imageId());
        }
        return null;
      }

      public WebProof webProof() {
        var receipts =
            ledger
                .mappers
                .events()
                .selectFactCode(
                    ledger.queryContext(),
                    lease.tenant(),
                    lease.taskId(),
                    "web-result:" + invocationId);
        if (!receipts.isEmpty()) {
          proofStep =
              JdbcLedger.first(
                  ledger
                      .mappers
                      .steps()
                      .selectInvocationStep(
                          ledger.queryContext(),
                          lease.tenant(),
                          lease.taskId(),
                          ledger.s(producer, "step_id")));
          return new WebProof(
              ledger.s(producer, "output_snapshot"),
              ledger.s(producer, "output_digest"),
              (String) receipts.getFirst().get("code"));
        }
        return null;
      }

      public String webProofDigest() {
        return WebFetchReceipts.digest(task, proofStep, producer, output);
      }

      public int succeed() {
        return ledger
            .mappers
            .invocations()
            .succeedRunning(
                ledger.queryContext(),
                output,
                Payloads.hash(output),
                lease.tenant(),
                invocationId,
                lease.taskId());
      }

      public void settleUnit() {
        ledger
            .mappers
            .reservations()
            .settleToolUnit(ledger.queryContext(), lease.tenant(), invocationId);
      }

      public void succeeded() {
        ledger.event(task, "result:" + invocationId, "tool.succeeded", null);
      }

      public void commitFence() {
        if (ledger.directoryTask(task)) ledger.todoCommitFence(lease);
      }

      public void validateMaterial() {
        ledger.checkMaterials(task);
      }

      public void stopEvaluationRun() {
        ledger
            .mappers
            .evaluations()
            .stopRun(ledger.queryContext(), lease.tenant(), ledger.s(task, "evaluation_run_id"));
      }
    };
  }
}
