package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportPort;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ComposeReportPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.PresentPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ReadPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.WritePlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted gateway assembly, with no model-controlled scope, operation ID, or CAS bypass. */
public final class WorkspaceRuntime {
  private final SecureWorkspaceStore workspace;
  private final Map<String, WorkspaceContracts.Contract> contracts;
  private final ReportPolicy reports = new ReportPolicy();

  public WorkspaceRuntime(SecureWorkspaceStore workspace) throws IOException {
    this.workspace = workspace;
    var loaded = new LinkedHashMap<String, WorkspaceContracts.Contract>();
    for (String name : WorkspaceContracts.NAMES)
      loaded.put(name, new WorkspaceContracts().load(name));
    contracts = Map.copyOf(loaded);
  }

  public WorkspaceContracts.Contract contract(String name) {
    var contract = contracts.get(name);
    if (contract == null) throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
    return contract;
  }

  public void prepareEvaluation(Lease lease, ExecutionLedger ledger) {
    ledger
        .evaluationFile(lease)
        .ifPresent(
            path -> {
              var file =
                  workspace.inspect(
                      lease.tenant(), lease.taskId(), path, () -> ledger.checkpoint(lease));
              ledger.bindEvaluationFile(
                  lease,
                  new ReportFile(
                      file.filePath(),
                      file.version(),
                      file.sha256(),
                      file.size(),
                      ReportPolicy.MEDIA));
            });
  }

  public void validate(String name, JsonNode input) {
    contract(name).in().validate(input);
    Set<String> allowed =
        switch (name) {
          case "read" -> Set.of("file_path", "offset", "limit");
          case "write" -> Set.of("file_path", "content");
          case "present" -> Set.of("files");
          case "compose_report" -> Set.of("file_path", "draft");
          default -> throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
        };
    input
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key))
                throw new IllegalArgumentException("Unknown tool argument");
            });
    if (name.equals("read")) {
      integer(input, "offset", 1, Integer.MAX_VALUE);
      integer(input, "limit", 2000, 2000);
    }
    if (name.equals("present")) {
      var files = input.path("files");
      if (files.isEmpty() || files.size() > 4)
        throw new IllegalArgumentException("Invalid deliverables");
      var paths = new java.util.HashSet<String>();
      files.forEach(
          file -> {
            if (!paths.add(file.path("path").asText()))
              throw new IllegalArgumentException("Duplicate path");
          });
    }
  }

  private int integer(JsonNode input, String name, int fallback, int max) {
    var value = input.get(name);
    if (value == null) return fallback;
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 1
        || value.intValue() > max) throw new IllegalArgumentException("Invalid line bound");
    return value.intValue();
  }

  public String execute(
      String name,
      JsonNode input,
      Lease lease,
      Invocation invocation,
      ExecutionLedger ledger,
      ArtifactStore store,
      BoundaryProbe probe) {
    // admit returns RUNNING only for new admission; all unconfirmed recovery is refused by
    // the ledger. Never catch a missing binding and rebind an older invocation.
    var port =
        workspace.bind(
            lease.tenant(), lease.taskId(), invocation.effectId(), () -> ledger.checkpoint(lease));
    var descriptor = contract(name).descriptor();
    var context =
        new ExecutionContext(invocation.id(), lease.deadline(), () -> ledger.checkpoint(lease));
    Object result =
        switch (name) {
          case "read" ->
              new ReadPlugin(port, descriptor)
                  .execute(
                      new ReadPlugin.Input(
                          input.path("file_path").asText(),
                          integer(input, "offset", 1, Integer.MAX_VALUE),
                          integer(input, "limit", 2000, 2000)),
                      context)
                  .output();
          case "write" ->
              new WritePlugin(port, descriptor)
                  .execute(
                      new WritePlugin.Input(
                          input.path("file_path").asText(), input.path("content").asText()),
                      context)
                  .output();
          case "compose_report" ->
              new ComposeReportPlugin(
                      (path, draft) -> {
                        var composed =
                            new ReportComposer().compose(path, draft, ledger.reportContext(lease));
                        probe.hit("C0", lease.taskId());
                        var written = port.write(path, composed.markdown());
                        probe.hit("C1", lease.taskId());
                        return composed.receipt(written);
                      },
                      descriptor)
                  .execute(
                      Payloads.read(input.toString(), ComposeReportPlugin.Input.class), context)
                  .output();
          case "present" ->
              new PresentPlugin(
                      port,
                      files -> present(files, lease, invocation, ledger, store, probe),
                      descriptor)
                  .execute(Payloads.read(input.toString(), PresentPlugin.Input.class), context)
                  .output();
          default -> throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
        };
    contract(name).out().validateObject(result);
    String output = Payloads.json(result);
    if (output.getBytes(StandardCharsets.UTF_8).length > 1048576)
      throw new FileFailure("TOOL_OUTPUT_TOO_LARGE");
    return output;
  }

  private ReportPort.Result present(
      List<FilePort.Snapshot> files,
      Lease lease,
      Invocation invocation,
      ExecutionLedger ledger,
      ArtifactStore store,
      BoundaryProbe probe) {
    var context = ledger.reportContext(lease);
    files.forEach(file -> reports.validate(file, context));
    probe.hit("P_BEFORE_STAGE", lease.taskId());
    var receipts =
        files.stream()
            .map(
                file ->
                    new ReportFile(
                        file.filePath(),
                        file.version(),
                        file.sha256(),
                        file.size(),
                        ReportPolicy.MEDIA))
            .toList();
    var uploads = ledger.stageReports(lease, invocation.id(), receipts);
    probe.hit("P0", lease.taskId());
    var publications = new ArrayList<Publication>();
    var outputFiles = new ArrayList<ReportPort.Published>();
    for (int index = 0; index < uploads.size(); index++) {
      var upload = uploads.get(index);
      var file = files.get(index);
      String key =
          store.write(
              lease.tenant(),
              upload.uploadId(),
              file.content().getBytes(StandardCharsets.UTF_8),
              file.sha256());
      store.read(lease.tenant(), key, file.sha256(), file.size());
      publications.add(new Publication(upload.id(), key));
      outputFiles.add(
          new ReportPort.Published(
              upload.id(),
              file.filePath(),
              file.version(),
              file.sha256(),
              file.size(),
              ReportPolicy.MEDIA));
    }
    var output = new ReportPort.Result(outputFiles);
    contract("present").out().validateObject(output);
    probe.hit("P1", lease.taskId());
    ledger.presentSucceeded(lease, invocation.id(), publications, Payloads.json(output));
    return output;
  }
}
