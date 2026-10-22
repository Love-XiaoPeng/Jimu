package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchQuery;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportPort;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ComposeReportPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.EditPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.GlobPlugin;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.GrepPlugin;
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
  private final NativeSearchRuntime searches;

  public WorkspaceRuntime(SecureWorkspaceStore workspace) throws IOException {
    this(workspace, new NativeSearchRuntime());
  }

  public WorkspaceRuntime(SecureWorkspaceStore workspace, NativeSearchRuntime searches)
      throws IOException {
    this.workspace = workspace;
    this.searches = searches;
    var loaded = new LinkedHashMap<String, WorkspaceContracts.Contract>();
    for (String name : WorkspaceContracts.NAMES)
      loaded.put(name, new WorkspaceContracts().load(name));
    contracts = Map.copyOf(loaded);
  }

  public void available(String name) {
    if (name.equals("glob") || name.equals("grep")) searches.requireAvailable();
  }

  public WorkspaceContracts.Contract contract(String name) {
    var contract = contracts.get(name);
    if (contract == null) throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
    return contract;
  }

  public void prepareEvaluation(Lease lease, ExecutionLedger ledger) {
    var pins = ledger.toolSnapshot(lease).pins();
    workspace.initialize(
        lease.tenant(),
        lease.taskId(),
        pins.keySet().stream().anyMatch(name -> Set.of("edit", "glob", "grep").contains(name)),
        () -> ledger.checkpoint(lease));
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
          case "edit" -> Set.of("file_path", "old_string", "new_string", "replace_all");
          case "glob" -> Set.of("pattern", "path", "cursor");
          case "grep" -> Set.of("pattern", "path", "include", "cursor");
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
    if (name.equals("edit")) {
      String old = input.path("old_string").asText(),
          replacement = input.path("new_string").asText();
      if (old.isEmpty()
          || old.equals(replacement)
          || !StandardCharsets.UTF_8.newEncoder().canEncode(old)
          || !StandardCharsets.UTF_8.newEncoder().canEncode(replacement)
          || old.getBytes(StandardCharsets.UTF_8).length > 16384
          || replacement.getBytes(StandardCharsets.UTF_8).length > 16384
          || input.path("file_path").asText().length() > 512
          || input.toString().getBytes(StandardCharsets.UTF_8).length > 65535)
        throw new IllegalArgumentException("Invalid edit argument");
    }
    if (name.equals("glob") || name.equals("grep")) {
      new NativeSearchQuery(
          name,
          input.path("pattern").asText(),
          input.has("path") ? input.path("path").asText() : null,
          input.has("include") ? input.path("include").asText() : null);
      NativeSearchQuery.cursor(input.has("cursor") ? input.path("cursor").asText() : null);
      if (input.toString().getBytes(StandardCharsets.UTF_8).length > 65535)
        throw new IllegalArgumentException("Invalid search input");
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
    boolean search = name.equals("glob") || name.equals("grep");
    var port =
        search
            ? null
            : workspace.bind(
                lease.tenant(),
                lease.taskId(),
                invocation.effectId(),
                () -> ledger.checkpoint(lease));
    var descriptor = contract(name).descriptor();
    var context =
        new ExecutionContext(invocation.id(), lease.deadline(), () -> ledger.checkpoint(lease));
    var searchPort =
        search
            ? searches.bind(
                workspace,
                ledger,
                (NativeSearchLedger) ledger,
                lease,
                invocation,
                name + ":" + descriptor.version() + ":" + descriptor.outputSha256(),
                probe)
            : null;
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
          case "edit" ->
              new EditPlugin((EditPort) port, descriptor)
                  .execute(
                      new EditPlugin.Input(
                          input.path("file_path").asText(),
                          input.path("old_string").asText(),
                          input.path("new_string").asText(),
                          input.path("replace_all").asBoolean(false)),
                      context)
                  .output();
          case "glob" ->
              new GlobPlugin(searchPort, descriptor)
                  .execute(
                      new GlobPlugin.Input(
                          input.path("pattern").asText(),
                          input.has("path") ? input.path("path").asText() : null,
                          input.has("cursor") ? input.path("cursor").asText() : null),
                      context)
                  .output();
          case "grep" ->
              new GrepPlugin(searchPort, descriptor)
                  .execute(
                      new GrepPlugin.Input(
                          input.path("pattern").asText(),
                          input.has("path") ? input.path("path").asText() : null,
                          input.has("include") ? input.path("include").asText() : null,
                          input.has("cursor") ? input.path("cursor").asText() : null),
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
    if (output.getBytes(StandardCharsets.UTF_8).length > (search ? 65536 : 1048576))
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
