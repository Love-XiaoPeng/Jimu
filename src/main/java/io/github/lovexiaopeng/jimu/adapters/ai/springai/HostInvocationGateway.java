package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 宿主工具的接纳、执行及提交；沿用原槽，暂停与提交不确定交由 attempt 收尾。 */
final class HostInvocationGateway {
  private final ExecutionLedger ledger;
  private final ArtifactStore store;
  private final FetchRuntime fetch;
  private final BoundaryProbe probe;
  private final WorkspaceRuntime workspace;
  private final McpRuntime mcp;
  private final ObjectMapper json = new ObjectMapper();
  private final SchemaContract inputSchema;
  private final SchemaContract outputSchema;

  HostInvocationGateway(
      ExecutionLedger ledger,
      ArtifactStore store,
      FetchRuntime fetch,
      BoundaryProbe probe,
      WorkspaceRuntime workspace,
      McpRuntime mcp)
      throws IOException {
    this.ledger = ledger;
    this.store = store;
    this.fetch = fetch;
    this.probe = probe;
    this.workspace = workspace;
    this.mcp = mcp;
    inputSchema = new SchemaContract(BuiltinManifestLoader.resource("input.schema.json"));
    outputSchema = new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"));
  }

  String invoke(Lease lease, String stepId, Call call, String input) {
    try {
      return invokeSlot(lease, stepId, call, input);
    } catch (FileFailure error) {
      throw new TaskFailure(409, error.code());
    }
  }

  private String invokeSlot(Lease lease, String stepId, Call call, String input) {
    if (call.tool().equals("working_directory"))
      return workspace.directories().invoke(ledger, lease, stepId, call, input, probe);
    if (call.tool().equals("session_search"))
      return invokePriorSessionSearch(lease, stepId, call, input);
    if (call.tool().equals("session_event_trace"))
      return invokeSessionTrace(lease, stepId, call, input);
    if (call.tool().equals("session_event_read")) return invokeSession(lease, stepId, call, input);
    if (call.tool().equals("session_event_search"))
      return invokeSessionSearch(lease, stepId, call, input);
    if (call.tool().equals("todo_write")) return invokeTodo(lease, stepId, call, input);
    if (call.tool().equals("ask_user_question")) return invokeQuestion(lease, stepId, call, input);
    if (io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger.NAMES.contains(call.tool()))
      return invokeMcp(lease, stepId, call, input);
    boolean file = !call.tool().equals("web_fetch");
    String version = file ? ledger.toolSnapshot(lease).pins().get(call.tool()).version() : null;
    var descriptor =
        file ? workspace.contract(call.tool(), version).descriptor() : fetch.descriptor();
    var outputContract = file ? workspace.contract(call.tool(), version).out() : outputSchema;
    JsonNode args;
    try {
      args =
          call.tool().equals("compose_report")
              ? ReportComposer.parse(input)
              : lease.capabilities().contains("working_directory")
                  ? new io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryContracts()
                      .json(input, 65535)
                  : json.readTree(input);
      if (file) workspace.validate(call.tool(), version, args);
      else {
        inputSchema.validate(args);
        fetch.validate(Payloads.read(input, FetchInput.class));
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
    probe.hit("T0_BEFORE", lease.taskId());
    ExecutionLedger.Invocation invocation;
    try {
      invocation =
          ledger.admit(lease, stepId, call, descriptor.version(), descriptor.outputSha256());
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unconfirmed) {
      if (lease.capabilities().contains("working_directory"))
        throw new io.github.lovexiaopeng.jimu.adapters.runtime.directory
            .DirectoryCommitUnconfirmed();
      throw unconfirmed;
    }
    if ("SUCCEEDED".equals(invocation.state())) {
      outputContract.validate(Payloads.read(invocation.output(), JsonNode.class));
      return invocation.output();
    }
    if (lease.capabilities().contains("working_directory")) {
      var canonical = (com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy();
      canonical.put(
          "file_path",
          ((io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger) ledger)
              .directoryFilePath(lease, invocation.id()));
      args = canonical;
    }
    probe.hit("T0", lease.taskId());
    String output;
    try {
      if (file)
        output = workspace.execute(call.tool(), args, lease, invocation, ledger, store, probe);
      else {
        var result =
            fetch.execute(
                Payloads.read(input, FetchInput.class),
                lease.tenant(),
                invocation.effectId(),
                new ExecutionContext(
                    invocation.id(), lease.deadline(), () -> ledger.checkpoint(lease)),
                observation -> ledger.observeWebFetch(lease, invocation.id(), observation));
        outputContract.validateObject(result);
        output = Payloads.json(result);
      }
      if (output.getBytes(StandardCharsets.UTF_8).length > 2097152)
        throw new TaskFailure(409, "TOOL_OUTPUT_TOO_LARGE");
      if (!file && fetch.publicMode()) {
        ledger.preserveWebFetchResult(lease, invocation.id(), output);
        probe.hit("WEB_RESULT", lease.taskId());
      }
    } catch (FileFailure error) {
      if ("WORKSPACE_UNAVAILABLE".equals(error.code())
          || "SEARCH_PROCESS_UNPROVEN".equals(error.code())
          || "SNAPSHOT_CORRUPT".equals(error.code())) {
        ledger.uncertain(lease, invocation.id());
        throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
      }
      ledger.toolFailed(lease, invocation.id(), error.code());
      throw new TaskFailure(409, error.code());
    } catch (TaskFailure error) {
      if (((call.tool().equals("glob") || call.tool().equals("grep"))
              && !error.code().equals("FENCE_REJECTED")
              && ((io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger) ledger)
                  .searchQuiescent(lease, invocation.id()))
          || file
              && (error.code().startsWith("REPORT_")
                  || error.code().equals("NO_EVIDENCE")
                  || error.code().equals("STORAGE_BUDGET_EXCEEDED")
                  || error.code().equals("SEARCH_CURSOR_INVALID")
                  || error.code().equals("SEARCH_RESULT_LIMIT")
                  || (call.tool().equals("present")
                      && error.code().equals("MODEL_BUDGET_EXCEEDED")))) {
        ledger.toolFailed(lease, invocation.id(), error.code());
        throw error;
      }
      ledger.uncertain(lease, invocation.id());
      throw error;
    } catch (RuntimeException error) {
      ledger.uncertain(lease, invocation.id());
      throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
    }
    if (call.tool().equals("present") || call.tool().equals("read_image")) {
      probe.hit("P2", lease.taskId());
      return output;
    }
    probe.hit("T1_BEFORE", lease.taskId());
    try {
      ledger.toolSucceeded(lease, invocation.id(), output);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unconfirmed) {
      if (lease.capabilities().contains("working_directory"))
        throw new io.github.lovexiaopeng.jimu.adapters.runtime.directory
            .DirectoryCommitUnconfirmed();
      throw unconfirmed;
    }
    if (call.tool().equals("glob") || call.tool().equals("grep"))
      probe.hit("S_COMMITTED", lease.taskId());
    if (call.tool().equals("compose_report")) probe.hit("C2", lease.taskId());
    probe.hit("T1", lease.taskId());
    if (call.tool().equals("write")) probe.hit("W1", lease.taskId());
    return output;
  }

  private String invokeSessionTrace(Lease lease, String stepId, Call call, String input) {
    if (!call.input().equals(input)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var contracts =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts();
    var canonical = contracts.input(input);
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort port =
        value -> {
          if (!canonical.equals(value)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          probe.hit("SESSION_TRACE_COMMIT_BEFORE", lease.taskId());
          String output =
              ((io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger) ledger)
                  .traceOrResult(lease, stepId, call, pin.version(), pin.digest());
          probe.hit("SESSION_TRACE_COMMIT_AFTER", lease.taskId());
          contracts.output(output);
          return output;
        };
    return new io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventTracePlugin(
            port, contracts.descriptor())
        .execute(
            canonical,
            new ExecutionContext(
                call.providerId(), lease.deadline(), () -> ledger.checkpoint(lease)))
        .output();
  }

  private String invokeSessionSearch(Lease lease, String stepId, Call call, String input) {
    if (!call.input().equals(input)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var contracts =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchContracts(
            lease.capabilities().contains("session_event_trace") ? 3 : 2);
    var canonical = contracts.input(input);
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort port =
        value -> {
          if (!canonical.equals(value)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          probe.hit("SESSION_SEARCH_COMMIT_BEFORE", lease.taskId());
          String output =
              ((io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger) ledger)
                  .searchOrResult(lease, stepId, call, pin.version(), pin.digest());
          probe.hit("SESSION_SEARCH_COMMIT_AFTER", lease.taskId());
          contracts.output(output);
          return output;
        };
    return new io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventSearchPlugin(
            port, contracts.descriptor())
        .execute(
            canonical,
            new ExecutionContext(
                call.providerId(), lease.deadline(), () -> ledger.checkpoint(lease)))
        .output();
  }

  private String invokePriorSessionSearch(Lease lease, String stepId, Call call, String input) {
    if (!call.input().equals(input)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var contracts =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionContracts();
    var canonical = contracts.input(input);
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort port =
        value -> {
          if (!canonical.equals(value)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          probe.hit("PRIOR_SESSION_COMMIT_BEFORE", lease.taskId());
          String output =
              ((io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger) ledger)
                  .scopedOrResult(lease, stepId, call, pin.version(), pin.digest());
          probe.hit("PRIOR_SESSION_COMMIT_AFTER", lease.taskId());
          contracts.output(output);
          return output;
        };
    return new io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionSearchPlugin(
            port, contracts.descriptor())
        .execute(
            canonical,
            new ExecutionContext(
                call.providerId(), lease.deadline(), () -> ledger.checkpoint(lease)))
        .output();
  }

  private String invokeSession(Lease lease, String stepId, Call call, String input) {
    if (!call.input().equals(input)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var contracts =
        new io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts(
            lease.capabilities().contains("session_search")
                ? 4
                : lease.capabilities().contains("session_event_trace")
                    ? 3
                    : lease.capabilities().contains("session_event_search") ? 2 : 1);
    var canonical = contracts.input(input);
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort port =
        value -> {
          if (!canonical.equals(value)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          probe.hit("SESSION_COMMIT_BEFORE", lease.taskId());
          String output =
              lease.capabilities().contains("session_search")
                  ? ((io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger) ledger)
                      .scopedOrResult(lease, stepId, call, pin.version(), pin.digest())
                  : ((io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger) ledger)
                      .readOrResult(lease, stepId, call, pin.version(), pin.digest());
          probe.hit("SESSION_COMMIT_AFTER", lease.taskId());
          contracts.output(output);
          return output;
        };
    return new io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventReadPlugin(
            port, contracts.descriptor())
        .execute(
            canonical,
            new ExecutionContext(
                call.providerId(), lease.deadline(), () -> ledger.checkpoint(lease)))
        .output();
  }

  private String invokeTodo(Lease lease, String stepId, Call call, String input) {
    var contracts = new io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts();
    var canonical = contracts.input(input);
    if (!call.input().equals(input)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    io.github.lovexiaopeng.jimu.plugin.api.TodoPort port =
        items -> {
          if (!items.equals(canonical.todos())) throw new TaskFailure(409, "INVOCATION_MISMATCH");
          probe.hit("TODO_COMMIT_BEFORE", lease.taskId());
          try {
            var result =
                ((io.github.lovexiaopeng.jimu.kernel.task.api.TodoLedger) ledger)
                    .replaceOrResult(lease, stepId, call, pin.version(), pin.digest());
            probe.hit("TODO_COMMIT_AFTER", lease.taskId());
            return result;
          } catch (org.springframework.dao.DataAccessException
              | org.springframework.transaction.TransactionException failure) {
            throw new io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoCommitUnconfirmed();
          }
        };
    var plugin =
        new io.github.lovexiaopeng.jimu.plugins.builtin.todo.TodoWritePlugin(
            port, contracts.descriptor());
    var result =
        plugin.execute(
            new io.github.lovexiaopeng.jimu.plugins.builtin.todo.TodoWritePlugin.Input(
                canonical.todos()),
            new ExecutionContext(stepId, lease.deadline(), () -> ledger.checkpoint(lease)));
    return Payloads.json(result.output());
  }

  private String invokeQuestion(Lease lease, String stepId, Call call, String input) {
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    var contracts =
        new io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts();
    var parsed = contracts.input(pin.version(), input);
    var descriptor = contracts.contract(pin.version()).descriptor();
    var port =
        (io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort)
            (qs, timeout) -> {
              probe.hit("QUESTION_OPEN_BEFORE", lease.taskId());
              var result =
                  ((io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger) ledger)
                      .openOrResult(lease, stepId, call, pin.version(), descriptor.outputSha256());
              if (result.suspended()) {
                probe.hit("QUESTION_OPEN_COMMITTED", lease.taskId());
                throw new io.github.lovexiaopeng.jimu.adapters.runtime.interaction
                    .QuestionSuspended();
              }
              return result.result();
            };
    var plugin =
        new io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin(
            port, descriptor);
    var result =
        plugin.execute(
            parsed, new ExecutionContext(stepId, lease.deadline(), () -> ledger.checkpoint(lease)));
    String output = Payloads.json(result.output());
    contracts.output(pin.version(), output);
    return output;
  }

  private String invokeMcp(Lease lease, String stepId, Call call, String input) {
    JsonNode args;
    try {
      args =
          io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpWire.parse(
              input.getBytes(StandardCharsets.UTF_8));
    } catch (TaskFailure error) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
    mcp.validate(call.tool(), args);
    var descriptor = mcp.contract(call.tool()).descriptor();
    probe.hit("T0_BEFORE", lease.taskId());
    var invocation =
        ledger.admit(lease, stepId, call, descriptor.version(), descriptor.outputSha256());
    var mcpLedger = (io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger) ledger;
    if ("SUCCEEDED".equals(invocation.state())) {
      mcpLedger.mcpServer(lease, args.path("server").asText());
      mcp.validateOutput(call.tool(), invocation.output());
      return invocation.output();
    }
    probe.hit("T0", lease.taskId());
    io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpRuntime.Result result;
    try {
      result = mcp.execute(call.tool(), args, lease, invocation, ledger);
    } catch (TaskFailure error) {
      if (List.of("FENCE_REJECTED", "SNAPSHOT_CORRUPT", "EFFECT_OUTCOME_UNKNOWN")
          .contains(error.code())) ledger.uncertain(lease, invocation.id());
      else ledger.toolFailed(lease, invocation.id(), error.code());
      throw error;
    } catch (RuntimeException error) {
      ledger.uncertain(lease, invocation.id());
      throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
    }
    probe.hit("T1_BEFORE", lease.taskId());
    mcpLedger.mcpSucceeded(lease, invocation.id(), result.output(), result.snapshot());
    probe.hit("T1", lease.taskId());
    return result.output();
  }
}
