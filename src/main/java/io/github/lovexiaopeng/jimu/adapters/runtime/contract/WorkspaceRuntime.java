package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchQuery;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.artifact.api.ArtifactErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Invocation;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.EditPort;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FileErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
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
  private final io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryRuntime directories;

  public io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryRuntime directories() {
    return directories;
  }

  private final Map<String, WorkspaceContracts.Contract> contracts;
  private final ReportPolicy reports = new ReportPolicy();
  private final NativeSearchRuntime searches;
  private final io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntime images;

  public io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntime images() {
    return images;
  }

  public WorkspaceRuntime(SecureWorkspaceStore workspace) throws IOException {
    this(workspace, new NativeSearchRuntime());
  }

  public WorkspaceRuntime(SecureWorkspaceStore workspace, NativeSearchRuntime searches)
      throws IOException {
    this(
        workspace,
        searches,
        new io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntime(
            null, new io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads()));
  }

  public WorkspaceRuntime(
      SecureWorkspaceStore workspace,
      NativeSearchRuntime searches,
      io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageRuntime images)
      throws IOException {
    this.workspace = workspace;
    this.directories =
        workspace == null
            ? null
            : new io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryRuntime(
                workspace);
    this.images = images;
    this.searches = searches;
    var loaded = new LinkedHashMap<String, WorkspaceContracts.Contract>();
    for (String name : WorkspaceContracts.NAMES)
      loaded.put(name, new WorkspaceContracts().load(name));
    loaded.put(
        "compose_report:0.2.0",
        new WorkspaceContracts().load(ToolCapability.COMPOSE_REPORT, "0.2.0"));
    contracts = Map.copyOf(loaded);
  }

  public void available(String name) {
    if (name.equals(ToolCapability.READ_IMAGE)) images.available();
    if (name.equals(ToolCapability.GLOB) || name.equals(ToolCapability.GREP))
      searches.requireAvailable();
  }

  public WorkspaceContracts.Contract contract(String name) {
    var contract = contracts.get(name);
    if (contract == null) throw new TaskFailure(403, ToolErrorCode.TOOL_NOT_AUTHORIZED);
    return contract;
  }

  public WorkspaceContracts.Contract contract(String name, String version) {
    var value =
        contracts.get(
            name.equals(ToolCapability.COMPOSE_REPORT) && version.equals("0.2.0")
                ? name + ":" + version
                : name);
    if (value == null || !value.descriptor().version().equals(version))
      throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
    return value;
  }

  public void prepareEvaluation(Lease lease, ExecutionLedger ledger) {
    if (lease.capabilities().contains(ToolCapability.WORKING_DIRECTORY)) {
      directories.prepare(ledger, lease);
      return;
    }
    var pins = ledger.toolSnapshot(lease).pins();
    workspace.initialize(
        lease.tenant(),
        lease.taskId(),
        pins.keySet().stream()
            .anyMatch(
                name ->
                    Set.of(ToolCapability.EDIT, ToolCapability.GLOB, ToolCapability.GREP)
                        .contains(name)),
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
    validate(name, "0.1.0", input);
  }

  public void validate(String name, String version, JsonNode input) {
    contract(name, version).in().validate(input);
    Set<String> allowed =
        switch (name) {
          case ToolCapability.READ -> Set.of("file_path", "offset", "limit");
          case ToolCapability.READ_IMAGE -> Set.of("file_path");
          case ToolCapability.WRITE -> Set.of("file_path", "content");
          case ToolCapability.PRESENT -> Set.of("files");
          case ToolCapability.COMPOSE_REPORT -> Set.of("file_path", "draft");
          case ToolCapability.EDIT ->
              Set.of("file_path", "old_string", "new_string", "replace_all");
          case ToolCapability.GLOB -> Set.of("pattern", "path", "cursor");
          case ToolCapability.GREP -> Set.of("pattern", "path", "include", "cursor");
          default -> throw new TaskFailure(403, ToolErrorCode.TOOL_NOT_AUTHORIZED);
        };
    input
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key))
                throw new IllegalArgumentException("Unknown tool argument");
            });
    if (name.equals(ToolCapability.READ)) {
      integer(input, "offset", 1, Integer.MAX_VALUE);
      integer(input, "limit", 2000, 2000);
    }
    if (name.equals(ToolCapability.EDIT)) {
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
    if (name.equals(ToolCapability.GLOB) || name.equals(ToolCapability.GREP)) {
      new NativeSearchQuery(
          name,
          input.path("pattern").asText(),
          input.has("path") ? input.path("path").asText() : null,
          input.has("include") ? input.path("include").asText() : null);
      NativeSearchQuery.cursor(input.has("cursor") ? input.path("cursor").asText() : null);
      if (input.toString().getBytes(StandardCharsets.UTF_8).length > 65535)
        throw new IllegalArgumentException("Invalid search input");
    }
    if (name.equals(ToolCapability.PRESENT)) {
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
    if (lease.capabilities().contains(ToolCapability.READ_IMAGE)) {
      String path = input.path("file_path").asText();
      if (path.equals("inputs/images") || path.startsWith("inputs/images/")) {
        if (name.equals(ToolCapability.READ)) throw new FileFailure(FileErrorCode.FS_NOT_TEXT);
        if (List.of(ToolCapability.WRITE, ToolCapability.EDIT, ToolCapability.COMPOSE_REPORT)
            .contains(name)) throw new FileFailure(FileErrorCode.FS_READ_ONLY);
      }
      if (name.equals(ToolCapability.PRESENT))
        for (var file : input.path("files"))
          if (file.path("path").asText().startsWith("inputs/images/"))
            throw new TaskFailure(409, ArtifactErrorCode.REPORT_INVALID);
    }
    // admit returns RUNNING only for new admission; all unconfirmed recovery is refused by
    // the ledger. Never catch a missing binding and rebind an older invocation.
    boolean search = name.equals(ToolCapability.GLOB) || name.equals(ToolCapability.GREP);
    var port =
        search
            ? null
            : (lease.capabilities().contains(ToolCapability.WORKING_DIRECTORY)
                    ? directories.workspace()
                    : workspace)
                .bind(
                    lease.tenant(),
                    lease.taskId(),
                    invocation.effectId(),
                    () -> ledger.checkpoint(lease));
    var selected = contract(name, ledger.toolSnapshot(lease).pins().get(name).version());
    var descriptor = selected.descriptor();
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
          case ToolCapability.READ_IMAGE ->
              new io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ReadImagePlugin(
                      images.bind(ledger, lease, invocation), descriptor)
                  .execute(
                      new io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ReadImagePlugin
                          .Input(input.path("file_path").asText()),
                      context)
                  .output();
          case ToolCapability.READ ->
              new ReadPlugin(port, descriptor)
                  .execute(
                      new ReadPlugin.Input(
                          input.path("file_path").asText(),
                          integer(input, "offset", 1, Integer.MAX_VALUE),
                          integer(input, "limit", 2000, 2000)),
                      context)
                  .output();
          case ToolCapability.WRITE ->
              new WritePlugin(port, descriptor)
                  .execute(
                      new WritePlugin.Input(
                          input.path("file_path").asText(), input.path("content").asText()),
                      context)
                  .output();
          case ToolCapability.EDIT ->
              new EditPlugin((EditPort) port, descriptor)
                  .execute(
                      new EditPlugin.Input(
                          input.path("file_path").asText(),
                          input.path("old_string").asText(),
                          input.path("new_string").asText(),
                          input.path("replace_all").asBoolean(false)),
                      context)
                  .output();
          case ToolCapability.GLOB ->
              new GlobPlugin(searchPort, descriptor)
                  .execute(
                      new GlobPlugin.Input(
                          input.path("pattern").asText(),
                          input.has("path") ? input.path("path").asText() : null,
                          input.has("cursor") ? input.path("cursor").asText() : null),
                      context)
                  .output();
          case ToolCapability.GREP ->
              new GrepPlugin(searchPort, descriptor)
                  .execute(
                      new GrepPlugin.Input(
                          input.path("pattern").asText(),
                          input.has("path") ? input.path("path").asText() : null,
                          input.has("include") ? input.path("include").asText() : null,
                          input.has("cursor") ? input.path("cursor").asText() : null),
                      context)
                  .output();
          case ToolCapability.COMPOSE_REPORT ->
              descriptor.version().equals("0.2.0")
                  ? new ComposeReportPlugin.Images(
                          (path, draft) -> {
                            var evidence =
                                ((io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger) ledger)
                                    .imageReportContext(lease, invocation.id());
                            var composed = new ImageReportComposer().compose(path, draft, evidence);
                            probe.hit("C0", lease.taskId());
                            var written = port.write(path, composed.markdown());
                            probe.hit("C1", lease.taskId());
                            return composed.receipt(written);
                          },
                          descriptor)
                      .execute(
                          Payloads.read(input.toString(), ComposeReportPlugin.Images.Input.class),
                          context)
                      .output()
                  : new ComposeReportPlugin(
                          (path, draft) -> {
                            var composed =
                                new ReportComposer()
                                    .compose(path, draft, ledger.reportContext(lease));
                            probe.hit("C0", lease.taskId());
                            var written = port.write(path, composed.markdown());
                            probe.hit("C1", lease.taskId());
                            return composed.receipt(written);
                          },
                          descriptor)
                      .execute(
                          Payloads.read(input.toString(), ComposeReportPlugin.Input.class), context)
                      .output();
          case ToolCapability.PRESENT ->
              new PresentPlugin(
                      port,
                      files -> present(files, lease, invocation, ledger, store, probe),
                      descriptor)
                  .execute(Payloads.read(input.toString(), PresentPlugin.Input.class), context)
                  .output();
          default -> throw new TaskFailure(403, ToolErrorCode.TOOL_NOT_AUTHORIZED);
        };
    selected.out().validateObject(result);
    String output = Payloads.json(result);
    if (output.getBytes(StandardCharsets.UTF_8).length > (search ? 65536 : 1048576))
      throw new FileFailure(ToolErrorCode.TOOL_OUTPUT_TOO_LARGE);
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
    if (!ledger
        .toolSnapshot(lease)
        .pins()
        .getOrDefault(ToolCapability.COMPOSE_REPORT, new ExecutionLedger.PluginPin("0.1.0", "", 0))
        .version()
        .equals("0.2.0")) files.forEach(file -> reports.validate(file, context));
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
    contract(ToolCapability.PRESENT).out().validateObject(output);
    probe.hit("P1", lease.taskId());
    ledger.presentSucceeded(lease, invocation.id(), publications, Payloads.json(output));
    return output;
  }
}
