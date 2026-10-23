package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import org.springframework.jdbc.core.JdbcTemplate;

/** Named SQL and module-proof operations, sharing the original transaction and fence. */
final class JdbcInvocationWorkflows implements InvocationWorkflowRecords {
  private final JdbcLedger ledger;
  private final JdbcTemplate db;

  JdbcInvocationWorkflows(JdbcLedger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  @Override
  public Admission lockAdmission(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest) {
    var task = ledger.fenced(lease, true);
    var budget = ledger.bucket(lease.tenant());
    var initial =
        ledger.one(
            "SELECT * FROM invocation WHERE tenant_id=? AND step_id=? AND slot=? AND task_id=?",
            lease.tenant(),
            stepId,
            call.slot(),
            lease.taskId());
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

      public void validateMaterial() {
        var material = ledger.material(task);
        if (material != null) {
          ledger.initialFile(task);
          var input = Payloads.read(call.input(), com.fasterxml.jackson.databind.JsonNode.class);
          if (call.tool().equals("web_fetch"))
            EvaluationMaterials.require(material.sources().containsKey(input.path("url").asText()));
          if (call.tool().equals("write"))
            EvaluationMaterials.require(
                !ledger
                    .rows(
                        "SELECT id FROM invocation WHERE tenant_id=? AND task_id=? AND tool='read' AND state='SUCCEEDED' LIMIT 1",
                        lease.tenant(),
                        lease.taskId())
                    .isEmpty());
          if (call.tool().equals("read") || call.tool().equals("write"))
            EvaluationMaterials.require(material.target().equals(input.path("file_path").asText()));
          if (call.tool().equals("present"))
            EvaluationMaterials.require(
                input.path("files").size() == 1
                    && material.target().equals(input.path("files").get(0).path("path").asText()));
        }
      }

      public Pin pin() {
        var pinned = ledger.tools(task).pins().get(call.tool());
        var installation =
            ledger.one(
                "SELECT * FROM installation WHERE tenant_id=? AND plugin_id=?",
                lease.tenant(),
                call.tool());
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
        db.update(
            "UPDATE task SET tools_used=tools_used+1 WHERE tenant_id=? AND id=?",
            lease.tenant(),
            lease.taskId());
        db.update(
            "UPDATE tenant_budget SET tool_used=tool_used+1 WHERE tenant_id=?", lease.tenant());
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
        db.update(
            "UPDATE invocation SET state='RUNNING' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            ledger.s(invocation, "id"));
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
        ledger.one(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=?",
            lease.tenant(),
            lease.taskId(),
            invocationId);
    return new Success() {
      public String tool() {
        return ledger.s(producer, "tool");
      }

      public void validateResultProof() {
        if ("read_image".equals(ledger.s(producer, "tool"))) {
          var committed =
              ledger.rows(
                  "SELECT image_id FROM task_image_object WHERE tenant_id=? AND task_id=? AND producer_invocation=? AND phase='COMMITTED'",
                  lease.tenant(),
                  lease.taskId(),
                  invocationId);
          var image =
              Payloads.read(output, io.github.lovexiaopeng.jimu.plugin.api.ImagePort.Result.class);
          if (committed.size() != 1
              || !committed.getFirst().get("image_id").equals(image.image().imageId()))
            throw new TaskFailure(409, "IMAGE_COMMIT_REQUIRED");
        }
        var receipts =
            ledger.rows(
                "SELECT code FROM event WHERE tenant_id=? AND task_id=? AND fact_id=?",
                lease.tenant(),
                lease.taskId(),
                "web-result:" + invocationId);
        if (!receipts.isEmpty()) {
          var step =
              ledger.one(
                  "SELECT * FROM step WHERE tenant_id=? AND task_id=? AND id=?",
                  lease.tenant(),
                  lease.taskId(),
                  producer.get("step_id"));
          if (!output.equals(producer.get("output_snapshot"))
              || !Payloads.hash(output).equals(producer.get("output_digest"))
              || !WebFetchReceipts.digest(task, step, producer, output)
                  .equals(receipts.getFirst().get("code")))
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
        }
      }

      public int succeed() {
        return db.update(
            "UPDATE invocation SET state='SUCCEEDED',output_snapshot=?,output_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=? AND task_id=? AND state='RUNNING'",
            output,
            Payloads.hash(output),
            lease.tenant(),
            invocationId,
            lease.taskId());
      }

      public void settleUnit() {
        db.update(
            "UPDATE reservation SET state='SETTLED',actual=1 WHERE tenant_id=? AND id=?",
            lease.tenant(),
            invocationId);
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
        db.update(
            "UPDATE evaluation_budget SET state='STOPPED' WHERE tenant_id=? AND id=?",
            lease.tenant(),
            ledger.s(task, "evaluation_run_id"));
      }
    };
  }
}
