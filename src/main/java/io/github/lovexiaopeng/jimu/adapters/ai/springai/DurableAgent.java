package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.http.ProviderHttp;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** One ToolCallingAdvisor loop. The model boundary and callbacks commit facts before returning. */
public final class DurableAgent implements AutoCloseable {
  // Neutral versioned wire snapshots; no Spring object is serialized.
  public record ToolCall(String id, String type, String name, String arguments) {}

  public record ToolReply(String id, String name, String data) {}

  public record WireMessage(
      String role, String content, List<ToolCall> calls, List<ToolReply> replies) {}

  public record Checkpoint(
      int version,
      String model,
      List<WireMessage> messages,
      List<String> capabilities,
      long epoch,
      boolean stream,
      String configDigest,
      String requestBody,
      String bodyDigest) {}

  public record Response(
      int version, String content, List<ToolCall> calls, Integer tokens, ModelUsage usage) {}

  private final ExecutionLedger ledger;
  private final ArtifactStore store;
  private final FetchRuntime fetch;
  private final WorkspaceRuntime workspace;
  private final BoundaryProbe probe;
  private final ModelProfiles models;
  private final OpenAiProtocol protocol = new OpenAiProtocol();
  private final ProviderHttp http = new ProviderHttp();
  private final ObjectMapper json = new ObjectMapper();
  private final SchemaContract inputSchema;
  private final SchemaContract outputSchema;
  private final ToolDefinition definition;

  public DurableAgent(
      ExecutionLedger ledger,
      ArtifactStore store,
      FetchRuntime fetch,
      BoundaryProbe probe,
      ModelProfiles models)
      throws IOException {
    this(ledger, store, fetch, probe, models, null);
  }

  public DurableAgent(
      ExecutionLedger ledger,
      ArtifactStore store,
      FetchRuntime fetch,
      BoundaryProbe probe,
      ModelProfiles models,
      WorkspaceRuntime workspace)
      throws IOException {
    this.workspace = workspace;
    this.ledger = ledger;
    this.store = store;
    this.fetch = fetch;
    this.probe = probe;
    this.models = models;
    inputSchema = new SchemaContract(BuiltinManifestLoader.resource("input.schema.json"));
    outputSchema = new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"));
    definition =
        new ToolDefinitionMapper()
            .map(
                fetch.descriptor(),
                new String(
                    BuiltinManifestLoader.resource("input.schema.json"), StandardCharsets.UTF_8));
  }

  public void run(Lease lease) {
    var definitions = definitions(lease);
    var modelSnapshot = ledger.modelSnapshot(lease);
    if (workspace != null) workspace.prepareEvaluation(lease, ledger);
    Session session = new Session(lease, modelSnapshot, definitions);
    var previous = ledger.checkpoint(lease);
    List<Message> messages =
        previous
            .map(
                step ->
                    restore(
                        Payloads.read(step.request(), Checkpoint.class),
                        modelSnapshot,
                        definitions))
            .orElseGet(
                () ->
                    fetch.publicMode() && lease.capabilities().contains("present")
                        ? List.of(
                            new SystemMessage(
                                new ReportPolicy().instruction(ledger.reportContext(lease))),
                            new UserMessage(lease.goal()))
                        : List.of(new UserMessage(lease.goal())));
    var manager = session.manager();
    var advisor = ToolCallingAdvisor.builder().toolCallingManager(manager).build();
    // This ChatModel performs HTTP only and has no internal tool execution or SDK retries.
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            return session.call(prompt);
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            // Complete upstream SSE is persisted before exposing a response to the sole advisor.
            return Flux.defer(() -> Flux.just(session.call(prompt)));
          }

          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model(modelSnapshot.model()).build();
          }
        };
    var client = ChatClient.builder(model).defaultAdvisors(advisor).build();
    var options =
        ToolCallingChatOptions.builder()
            .model(modelSnapshot.model())
            .toolCallbacks(session.discoveryCallbacks())
            .build();
    var request = client.prompt(new Prompt(messages, options));
    var response =
        modelSnapshot.stream()
            ? request.stream().chatResponse().blockLast(Duration.ofSeconds(180))
            : request.call().chatResponse();
    if (response == null) throw new TaskFailure(502, "MODEL_STREAM_INCOMPLETE");
    if (response.hasToolCalls()) throw new TaskFailure(409, "MODEL_LOOP_INCOMPLETE");
    String summary = response.getResult().getOutput().getText();
    if (fetch.publicMode()) {
      probe.hit("M3", lease.taskId());
      ledger.finish(lease, summary); // The ledger requires an actual published present report.
      probe.hit("DONE", lease.taskId());
      return;
    }
    byte[] bytes = summary.getBytes(StandardCharsets.UTF_8);
    // A task without tool output has a summary but no tool-produced artifact.
    if (session.hadTools
        || previous.isPresent()
            && !Payloads.read(
                    previous.get().response() == null
                        ? "{\"version\":1,\"calls\":[]}"
                        : previous.get().response(),
                    Response.class)
                .calls()
                .isEmpty()) {
      var upload = ledger.stage(lease, Payloads.hash(bytes), bytes.length);
      probe.hit("A0", lease.taskId());
      if ("STAGING".equals(upload.state())) {
        String key = store.write(lease.tenant(), upload.uploadId(), bytes, upload.hash());
        probe.hit("A1", lease.taskId());
        ledger.verified(lease, upload.id(), key);
      } else store.read(lease.tenant(), upload.key(), upload.hash(), upload.size());
      ledger.publish(lease, upload.id());
      probe.hit("A2", lease.taskId());
    }
    probe.hit("M3", lease.taskId());
    ledger.finish(lease, summary);
    probe.hit("DONE", lease.taskId());
  }

  private List<ToolDefinition> definitions(Lease lease) {
    var pins = ledger.toolSnapshot(lease).pins();
    var result = new ArrayList<ToolDefinition>();
    for (String name : lease.capabilities()) {
      boolean web = name.equals("web_fetch");
      if (!web && (!fetch.publicMode() || workspace == null))
        throw new TaskFailure(503, "WORKSPACE_NOT_CONFIGURED");
      var descriptor = web ? fetch.descriptor() : workspace.contract(name).descriptor();
      var pin = pins.get(name);
      if (pin == null
          || !descriptor.version().equals(pin.version())
          || !descriptor.outputSha256().equals(pin.digest()))
        throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
      if (web) {
        fetch.available();
        result.add(definition);
      } else
        result.add(
            ToolDefinition.builder()
                .name(name)
                .description(
                    switch (name) {
                      case "read" ->
                          "Read a UTF-8 task file with line numbers; observe missing files before write";
                      case "write" ->
                          "Create or replace UTF-8 task file using the host's observed version";
                      case "present" ->
                          "Publish existing observed Markdown reports as immutable authorized deliverables";
                      default -> throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
                    })
                .inputSchema(workspace.contract(name).input())
                .build());
    }
    return List.copyOf(result);
  }

  private List<Message> restore(
      Checkpoint checkpoint, ModelSnapshot snapshot, List<ToolDefinition> definitions) {
    if (checkpoint.version() != 2) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    if (!ModelProfiles.digest(snapshot).equals(checkpoint.configDigest()))
      throw new TaskFailure(409, "MODEL_CONFIG_UNAVAILABLE");
    if (!Payloads.hash(checkpoint.requestBody()).equals(checkpoint.bodyDigest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!checkpoint
        .requestBody()
        .equals(protocol.body(snapshot, checkpoint.messages(), definitions)))
      throw new TaskFailure(409, "MODEL_CONFIG_UNAVAILABLE");
    return checkpoint.messages().stream().map(this::message).toList();
  }

  private Message message(WireMessage message) {
    return switch (message.role()) {
      case "user" -> new UserMessage(message.content());
      case "system" -> new SystemMessage(message.content());
      case "assistant" ->
          assistant(new Response(2, message.content(), message.calls(), null, null));
      case "tool" ->
          ToolResponseMessage.builder()
              .responses(
                  message.replies().stream()
                      .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.data()))
                      .toList())
              .build();
      default -> throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    };
  }

  private WireMessage wire(Message message) {
    List<ToolCall> calls =
        message instanceof AssistantMessage a
            ? a.getToolCalls().stream()
                .map(c -> new ToolCall(c.id(), c.type(), c.name(), c.arguments()))
                .toList()
            : List.of();
    List<ToolReply> replies =
        message instanceof ToolResponseMessage t
            ? t.getResponses().stream()
                .map(r -> new ToolReply(r.id(), r.name(), r.responseData()))
                .toList()
            : List.of();
    return new WireMessage(message.getMessageType().getValue(), message.getText(), calls, replies);
  }

  private AssistantMessage assistant(Response response) {
    return AssistantMessage.builder()
        .content(response.content() == null ? "" : response.content())
        .toolCalls(
            response.calls().stream()
                .map(c -> new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments()))
                .toList())
        .build();
  }

  private final class Session {
    private final Lease lease;
    private final ModelSnapshot modelSnapshot;
    private final List<ToolDefinition> definitions;
    private ExecutionLedger.Step step;
    private List<Call> calls = List.of();
    private boolean hadTools;

    Session(Lease lease, ModelSnapshot modelSnapshot, List<ToolDefinition> definitions) {
      this.definitions = definitions;
      this.modelSnapshot = modelSnapshot;
      this.lease = lease;
    }

    private ChatResponse call(Prompt prompt) {
      var history = prompt.getInstructions().stream().map(DurableAgent.this::wire).toList();
      String body = protocol.body(modelSnapshot, history, definitions);
      var checkpoint =
          new Checkpoint(
              2,
              modelSnapshot.model(),
              history,
              lease.capabilities(),
              lease.installationEpoch(),
              modelSnapshot.stream(),
              ModelProfiles.digest(modelSnapshot),
              body,
              Payloads.hash(body));
      step = ledger.prepare(lease, Payloads.json(checkpoint));
      if (!history.isEmpty()
          && history.getLast().replies().stream().anyMatch(reply -> reply.name().equals("read")))
        probe.hit("READ_MODEL_PREPARED", lease.taskId());
      probe.hit("M0", lease.taskId());
      Response response;
      if (step.response() != null) response = Payloads.read(step.response(), Response.class);
      else {
        String dispatch = ledger.dispatchModel(lease, step.id());
        probe.hit("M1", lease.taskId());
        ledger.checkpoint(lease);
        try {
          response = request(checkpoint);
        } catch (OpenAiProtocol.InvalidResponse invalid) {
          String code = checkpoint.stream() ? "MODEL_STREAM_INCOMPLETE" : "MODEL_RESPONSE_INVALID";
          ledger.modelFailed(lease, dispatch, code, invalid.usage());
          probe.hit("MODEL_FAILURE_COMMITTED", lease.taskId());
          throw new TaskFailure(502, code);
        } catch (TaskFailure error) {
          ledger.modelFailed(lease, dispatch, error.code(), null);
          probe.hit("MODEL_FAILURE_COMMITTED", lease.taskId());
          throw error;
        }
        probe.hit("M1_RESPONSE", lease.taskId());
        try {
          ledger.modelResponded(
              lease,
              step.id(),
              dispatch,
              Payloads.json(response),
              slots(response),
              response.tokens(),
              response.usage());
        } finally {
          // Also reached on the post-commit rejection, before WorkerLoop can persist failure.
          probe.hit("MODEL_RESPONSE_COMMITTED", lease.taskId());
        }
      }
      if (response.version() != 2) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
      calls = slots(response);
      hadTools =
          hadTools
              || !calls.isEmpty()
              || prompt.getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage);
      probe.hit("M2", lease.taskId());
      return new ChatResponse(List.of(new Generation(assistant(response))));
    }

    private List<Call> slots(Response response) {
      List<Call> result = new ArrayList<>();
      for (int index = 0; index < response.calls().size(); index++) {
        var call = response.calls().get(index);
        result.add(new Call(index + ":" + call.id(), call.id(), call.name(), call.arguments()));
      }
      return List.copyOf(result);
    }

    private List<ToolCallback> discoveryCallbacks() {
      return definitions.stream()
          .map(
              definition ->
                  (ToolCallback)
                      new ToolCallback() {
                        @Override
                        public ToolDefinition getToolDefinition() {
                          return definition;
                        }

                        @Override
                        public String call(String input) {
                          throw new IllegalStateException("Unbound callback");
                        }
                      })
          .toList();
    }

    private ToolCallingManager manager() {
      var delegate =
          DefaultToolCallingManager.builder()
              .resolutionFallbackEnabled(false)
              .maxTotalToolCalls(20)
              .maxCallsPerTool(20)
              .build();
      return new ToolCallingManager() {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
          return definitions;
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
          var byName = new HashMap<String, List<Call>>();
          for (Call call : calls)
            byName.computeIfAbsent(call.tool(), name -> new ArrayList<>()).add(call);
          List<ToolCallback> callbacks = new ArrayList<>();
          byName.forEach(
              (name, slots) -> {
                var bound =
                    definitions.stream()
                        .filter(d -> d.name().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new TaskFailure(403, "TOOL_NOT_AUTHORIZED"));
                var counter = new AtomicInteger();
                callbacks.add(
                    new ToolCallback() {
                      @Override
                      public ToolDefinition getToolDefinition() {
                        return bound;
                      }

                      @Override
                      public String call(String input) {
                        return invoke(slots.get(counter.getAndIncrement()), input);
                      }
                    });
              });
          var options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
          return delegate.executeToolCalls(new Prompt(prompt.getInstructions(), options), response);
        }
      };
    }

    private String invoke(Call call, String input) {
      boolean file = !call.tool().equals("web_fetch");
      var descriptor = file ? workspace.contract(call.tool()).descriptor() : fetch.descriptor();
      var outputContract = file ? workspace.contract(call.tool()).out() : outputSchema;
      JsonNode args;
      try {
        args = json.readTree(input);
        if (file) workspace.validate(call.tool(), args);
        else {
          inputSchema.validate(args);
          fetch.validate(Payloads.read(input, FetchInput.class));
        }
      } catch (IOException | IllegalArgumentException e) {
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
      }
      probe.hit("T0_BEFORE", lease.taskId());
      var invocation =
          ledger.admit(lease, step.id(), call, descriptor.version(), descriptor.outputSha256());
      if ("SUCCEEDED".equals(invocation.state())) {
        outputContract.validate(Payloads.read(invocation.output(), JsonNode.class));
        return invocation.output();
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
                      invocation.id(), lease.deadline(), () -> ledger.checkpoint(lease)));
          outputContract.validateObject(result);
          output = Payloads.json(result);
        }
        if (output.getBytes(StandardCharsets.UTF_8).length > 2097152)
          throw new TaskFailure(409, "TOOL_OUTPUT_TOO_LARGE");
      } catch (FileFailure error) {
        if ("WORKSPACE_UNAVAILABLE".equals(error.code())) {
          ledger.uncertain(lease, invocation.id());
          throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
        }
        ledger.toolFailed(lease, invocation.id(), error.code());
        throw new TaskFailure(409, error.code());
      } catch (TaskFailure error) {
        if (file
            && (error.code().startsWith("REPORT_")
                || error.code().equals("NO_EVIDENCE")
                || error.code().equals("STORAGE_BUDGET_EXCEEDED")
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
      if (call.tool().equals("present")) {
        probe.hit("P2", lease.taskId());
        return output;
      }
      probe.hit("T1_BEFORE", lease.taskId());
      ledger.toolSucceeded(lease, invocation.id(), output);
      probe.hit("T1", lease.taskId());
      if (call.tool().equals("write")) probe.hit("W1", lease.taskId());
      return output;
    }

    private Response request(Checkpoint checkpoint) throws OpenAiProtocol.InvalidResponse {
      models.admit(modelSnapshot);
      if (!Payloads.hash(checkpoint.requestBody()).equals(checkpoint.bodyDigest()))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      models.checkInput(modelSnapshot, checkpoint.requestBody());
      Duration remaining = Duration.between(Instant.now(), lease.deadline());
      if (remaining.isNegative() || remaining.isZero())
        throw new TaskFailure(409, "DEADLINE_EXCEEDED");
      Duration configured = Duration.ofMillis(modelSnapshot.requestTimeoutMs());
      Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
      try {
        var response =
            http.post(
                URI.create(modelSnapshot.baseUrl() + "/chat/completions"),
                checkpoint.requestBody().getBytes(StandardCharsets.UTF_8),
                timeout,
                models.credential(modelSnapshot),
                Duration.ofMillis(modelSnapshot.connectTimeoutMs()),
                modelSnapshot.responseBytes());
        String error =
            switch (response.statusCode()) {
              case 200 -> null;
              case 401, 403 -> "MODEL_AUTH_FAILED";
              case 404 -> "MODEL_NOT_FOUND";
              case 429 -> "MODEL_RATE_LIMITED";
              default -> "MODEL_UNAVAILABLE";
            };
        if (error != null) throw new TaskFailure(502, error);
        if (response.body().length > modelSnapshot.responseBytes())
          throw new TaskFailure(502, "MODEL_RESPONSE_INVALID");
        try {
          return protocol.parse(response.body(), checkpoint.stream(), lease.capabilities());
        } catch (OpenAiProtocol.InvalidResponse invalid) {
          throw invalid;
        } catch (IOException e) {
          throw new TaskFailure(
              502, checkpoint.stream() ? "MODEL_STREAM_INCOMPLETE" : "MODEL_RESPONSE_INVALID");
        }
      } catch (OpenAiProtocol.InvalidResponse invalid) {
        throw invalid;
      } catch (java.net.http.HttpTimeoutException e) {
        throw new TaskFailure(502, "MODEL_TIMEOUT");
      } catch (IOException e) {
        throw new TaskFailure(502, "MODEL_UNAVAILABLE");
      }
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
