package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactErrorCode;
import io.github.lovexiaopeng.jimu.kernel.invocation.api.InvocationState;
import io.github.lovexiaopeng.jimu.kernel.model.api.ModelErrorCode;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords.Definition;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords.Material;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.model.service.MaterialEvidenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.util.List;
import java.util.Optional;

/** 原材料资格、历史遍历/媒体投影、图像来源规则，保留原调用顺序和事务形状。 */
public final class MaterialEvidenceServiceImpl implements MaterialEvidenceService {
  private final MaterialEvidenceRecords records;

  public MaterialEvidenceServiceImpl(MaterialEvidenceRecords records) {
    this.records = records;
  }

  private static void require(boolean valid) {
    if (!valid) throw new TaskFailure(409, ModelErrorCode.EVALUATION_MATERIAL_MISMATCH);
  }

  public Material material(Row task) {
    if (task.evaluationSnapshotObject() == null) return null;
    String json = task.materialSnapshot();
    require(json != null && records.hash(json).equals(task.materialDigest()));
    var stored = records.material(json);
    var binding = records.binding(task.evaluationSnapshot());
    var expected = records.load(records.plan(binding.planId()), binding.sample());
    require(
        expected.equals(stored)
            && stored.goal().equals(task.goal())
            && records
                .hash(
                    stored
                        .systemTemplate()
                        .replace("{{HOST_REPORT_TIME}}", task.reportTime().toString()))
                .equals(task.materialSystemDigest()));
    return stored;
  }

  public void checkMaterials(Row task) {
    var material = material(task);
    if (material == null) return;
    for (var invocation : records.materialSources(task.tenantId(), task.id())) {
      String output = invocation.outputSnapshot();
      require(output != null && records.hash(output).equals(invocation.outputDigest()));
      var input = records.readJson(invocation.inputSnapshot());
      records.source(material, input.path("url").asText(), output);
    }
    if (task.materialFileSnapshotObject() != null) {
      var initial = initialFile(task);
      boolean written = false;
      for (var invocation : records.materialFileHistory(task.tenantId(), task.id())) {
        if (invocation.tool().equals(ToolCapability.WRITE)) {
          written = true;
          continue;
        }
        if (written) continue;
        String output = invocation.outputSnapshot();
        require(records.hash(output).equals(invocation.outputDigest()));
        var read = records.readJson(output);
        if (material.initialContent() != null) {
          var visible = new StringBuilder();
          String[] lines = material.initialContent().split("\n", -1);
          for (int index = 0; index < lines.length; index++)
            visible.append(index + 1).append(": ").append(lines[index]).append('\n');
          require(
              read.path("offset").asInt() == 1
                  && read.path("content").asText().equals(visible.toString()));
        }
        require(
            read.path("filePath").asText().equals(initial.path())
                && read.path("version").asLong() == initial.version()
                && read.path("size").asLong() == initial.size()
                && java.util.Objects.equals(
                    read.path("sha256").isNull() ? null : read.path("sha256").asText(),
                    initial.sha256()));
      }
    }
  }

  @Override
  public Optional<String> evaluationFile(Lease lease) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var material = material(task);
          if (material == null) return Optional.empty();
          boolean written =
              !records.successfulMaterialWrite(lease.tenant(), lease.taskId()).isEmpty();
          if (written) {
            initialFile(task);
            return Optional.empty();
          }
          // A restart before first dispatch may inspect again, but may never replace its original
          // receipt.
          return Optional.of(material.target());
        });
  }

  public ReportFile initialFile(Row task) {
    String json = task.materialFileSnapshot();
    require(json != null && records.hash(json).equals(task.materialFileDigest()));
    return records.file(json);
  }

  @Override
  public void bindEvaluationFile(Lease lease, ReportFile file) {
    records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          var material = material(task);
          require(material != null && material.target().equals(file.path()));
          String content = material.initialContent();
          require(
              content == null
                  ? file.version() == 0 && file.sha256() == null && file.size() == 0
                  : file.version() > 0
                      && records.hash(content).equals(file.sha256())
                      && content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                          == file.size());
          if (task.materialFileSnapshotObject() != null) require(initialFile(task).equals(file));
          else {
            require(records.existingMaterialStep(lease.tenant(), lease.taskId()).isEmpty());
            String json = records.json(file);
            records.bindMaterialReceipt(json, records.hash(json), lease.tenant(), lease.taskId());
          }
          return null;
        });
  }

  public void materialBody(Row task, String request, int ordinal) {
    var material = material(task);
    boolean business = records.businessTask(task);
    boolean media = task.recordVersion() == 3;
    if (material == null && !business && !media) return;
    var wire = records.readJson(request);
    if (material != null) {
      initialFile(task);
      records.body(material, wire.path("requestBody").asText(), task.reportTime().toString());
    }
    // Step N's request contains exactly the confirmed history through N-1. Its own
    // outputs may already be committed on recovery and must not enter this prefix.
    var preceding = records.materialPrecedingSteps(task.tenantId(), task.id(), ordinal);
    require(ordinal > 0 && preceding.size() == ordinal - 1);
    var body = records.readJson(wire.path("requestBody").asText());
    var expected = records.readArray("[]");
    if (business || media) {
      if (records.caps(task).contains(ToolCapability.PRESENT)) {
        var reports = records.reportInstructions();
        var context = new ReportContext(task.reportTime(), List.of());
        String instruction = body.path("messages").path(0).path("content").asText();
        // Recovery retains the original fixed template; no model-selected instruction is allowed.
        require(
            media
                ? records.imageInstruction(context).equals(instruction)
                : records.tools(task).pins().containsKey(ToolCapability.COMPOSE_REPORT)
                    ? records.compositionInstruction(context).equals(instruction)
                    : List.of(reports.instruction(context), reports.currentInstruction(context))
                        .contains(instruction));
        if (!preceding.isEmpty()) {
          String first = preceding.getFirst().requestSnapshot();
          require(records.hash(first).equals(preceding.getFirst().requestDigest()));
          var original = records.readJson(first);
          var originalBody = records.readJson(original.path("requestBody").asText());
          require(
              instruction.equals(originalBody.path("messages").path(0).path("content").asText()));
        }
        expected.addObject().put("role", "system").put("content", instruction);
      }
      expected.addObject().put("role", "user").put("content", task.goal());
      if (media)
        expected
            .addObject()
            .put("role", "user")
            .put("content", records.imageDirectory(records.imageSnapshot(task)));
    } else {
      expected.add(body.path("messages").get(0));
      expected.add(body.path("messages").get(1));
    }
    int position = 0;
    var seen = new java.util.HashSet<String>();
    for (var prior : preceding) {
      String response = prior.responseSnapshot();
      require(
          prior.ordinal() == ++position
              && response != null
              && records.hash(response).equals(prior.responseDigest()));
      var original = records.readJson(response);
      require(original.path("version").asInt() == 2 && original.path("calls").isArray());
      var assistant = expected.addObject().put("role", "assistant");
      assistant.put("content", original.path("content").asText(""));
      var invocations = records.materialStepInvocations(task.tenantId(), task.id(), prior.id());
      require(invocations.size() == original.path("calls").size());
      int slot = 0;
      for (var call : original.path("calls")) {
        var invocation = invocations.get(slot);
        String input = invocation.inputSnapshot();
        String output = invocation.outputSnapshot();
        require(
            (slot + ":" + call.path("id").asText()).equals(invocation.slot())
                && call.path("id").asText().equals(invocation.providerId())
                && call.path("name").asText().equals(invocation.tool())
                && call.path("type").asText().equals("function")
                && call.path("arguments").asText().equals(input)
                && records.hash(input).equals(invocation.inputDigest())
                && invocation.state().equals(InvocationState.SUCCEEDED)
                && output != null
                && records.hash(output).equals(invocation.outputDigest()));
        var emitted = assistant.withArray("tool_calls").addObject();
        emitted.put("id", call.path("id").asText()).put("type", "function");
        emitted
            .putObject("function")
            .put("name", call.path("name").asText())
            .put("arguments", input);
        slot++;
      }
      for (var invocation : invocations)
        expected
            .addObject()
            .put("role", "tool")
            .put("tool_call_id", invocation.providerId())
            .put("content", invocation.outputSnapshot());
      if (media)
        for (var image : records.stepImages(task, prior.id())) {
          var ref = image.result();
          if (seen.add(records.imageIdentity(ref)))
            expected.add(
                records.imageProvider(
                    ref,
                    records.readImage(
                        task.tenantId(), image.key(), ref.image().sha256(), ref.image().size())));
        }
    }
    require(expected.equals(body.path("messages")));
    if (media) {
      var cp = records.checkpoint(request);
      if (cp.version() != 3
          || request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12582912
          || !records.hash(cp.requestBody()).equals(cp.bodyDigest())
          || !records.modelDigest(records.snapshot(task)).equals(cp.configDigest()))
        throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
      // Every serialized reference is reloaded from this task and projected into exact data bytes.
      var definitions = new java.util.ArrayList<Definition>();
      int toolIndex = 0;
      for (String name : records.caps(task)) {
        var pin = records.tools(task).pins().get(name);
        try {
          String schema;
          if (name.equals(ToolCapability.WEB_FETCH))
            schema = new String(records.builtinInput(), java.nio.charset.StandardCharsets.UTF_8);
          else {
            var contract = records.workspaceContract(name, pin.version());
            if (!contract.descriptor().outputSha256().equals(pin.digest()))
              throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
            schema = contract.input();
          }
          var saved = body.path("tools").path(toolIndex++).path("function");
          String description = records.description(name);
          if (!saved.path("name").asText().equals(name)
              || !saved.path("description").asText().equals(description)
              || !saved.path("parameters").equals(records.readJson(schema)))
            throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
          definitions.add(records.definition(name, description, schema));
        } catch (java.io.IOException invalid) {
          throw new TaskFailure(503, TaskErrorCode.PLUGIN_SNAPSHOT_UNAVAILABLE);
        }
      }
      String projected =
          records.project(
              records.snapshot(task),
              cp.messages(),
              definitions,
              ref -> {
                var object = records.imageObject(task, ref);
                return records.readImage(
                    task.tenantId(), object.key(), ref.image().sha256(), ref.image().size());
              });
      if (!projected.equals(cp.requestBody()))
        throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
      records.checkInput(records.snapshot(task), projected);
    }
  }

  @Override
  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Lease lease, String invocation) {
    return records.leaseTransaction(
        lease, () -> imageReportContext(records.fenced(lease, true), invocation));
  }

  public io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence imageReportContext(
      Row task, String invocation) {
    var compose = records.imageComposition(task.tenantId(), task.id(), invocation);
    var step = records.imageCompositionStep(task.tenantId(), task.id(), compose.stepId());
    if (step.responseSnapshotObject() == null
        || !records.hash(step.responseSnapshot()).equals(step.responseDigestObject())
        || step.dispatchIdObject() == null)
      throw new TaskFailure(409, ArtifactErrorCode.REPORT_IMAGE_SOURCE_INVALID);
    var dispatch = records.imageCompositionDispatch(task.tenantId(), task.id(), step.dispatchId());
    if (!"RESPONDED".equals(dispatch.state())
        || !records.hash(step.requestSnapshot()).equals(step.requestDigestObject()))
      throw new TaskFailure(409, ArtifactErrorCode.REPORT_IMAGE_SOURCE_INVALID);
    var checkpoint = records.checkpoint(step.requestSnapshot());
    if (checkpoint.version() != 3
        || !records.hash(checkpoint.requestBody()).equals(checkpoint.bodyDigest())
        || !task.modelDigest().equals(checkpoint.configDigest()))
      throw new TaskFailure(409, ArtifactErrorCode.REPORT_IMAGE_SOURCE_INVALID);
    var body = records.readJson(checkpoint.requestBody());
    var result =
        new java.util.ArrayList<
            io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ImageSource>();
    for (var message : checkpoint.messages())
      if (message.images() != null)
        for (var ref : message.images()) {
          var object = records.imageObject(task, ref);
          byte[] bytes =
              records.readImage(
                  task.tenantId(), object.key(), ref.image().sha256(), ref.image().size());
          var part = records.imageProvider(ref, bytes);
          boolean present = false;
          for (var actual : body.path("messages")) if (actual.equals(part)) present = true;
          if (!present) throw new TaskFailure(409, ArtifactErrorCode.REPORT_IMAGE_SOURCE_INVALID);
          var source =
              records.imageSourceDigest(task.tenantId(), task.id(), ref.sourceRef().substring(6));
          result.add(
              new io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ImageSource(
                  ref,
                  source.outputDigest(),
                  step.id(),
                  step.dispatchId(),
                  checkpoint.configDigest(),
                  checkpoint.bodyDigest(),
                  step.responseDigest()));
        }
    return new io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger.ReportEvidence(
        records.reportContext(task),
        List.copyOf(result),
        !records.snapshot(task).paid() && records.localEvaluation(records.snapshot(task)));
  }
}
