package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.http.ProviderHttp;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.ConfiguredOriginReader;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
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

/** One ToolCallingAdvisor loop. The model boundary and callbacks commit facts before returning. */
public final class DurableAgent implements AutoCloseable {
  // These v1 records are neutral wire snapshots; no Spring object is serialized.
  public record ToolCall(String id, String type, String name, String arguments) {}

  public record ToolReply(String id, String name, String data) {}

  public record WireMessage(
      String role, String content, List<ToolCall> calls, List<ToolReply> replies) {}

  public record Checkpoint(
      int version,
      String model,
      List<WireMessage> messages,
      List<String> capabilities,
      long epoch) {}

  public record Response(int version, String content, List<ToolCall> calls, Integer tokens) {}

  private final ExecutionLedger ledger;
  private final ArtifactStore store;
  private final WebFetchPlugin plugin;
  private final BoundaryProbe probe;
  private final URI origin;
  private final java.util.Map<String, String> credentials;
  private final ProviderHttp http = new ProviderHttp();
  private final ObjectMapper json = new ObjectMapper();
  private final SchemaContract inputSchema;
  private final SchemaContract outputSchema;
  private final ToolDefinition definition;

  public DurableAgent(
      ExecutionLedger ledger,
      ArtifactStore store,
      WebFetchPlugin plugin,
      BoundaryProbe probe,
      URI origin,
      java.util.Map<String, String> credentials)
      throws IOException {
    this.ledger = ledger;
    this.store = store;
    this.plugin = plugin;
    this.probe = probe;
    this.origin = origin;
    this.credentials = java.util.Map.copyOf(credentials);
    inputSchema = new SchemaContract(BuiltinManifestLoader.resource("input.schema.json"));
    outputSchema = new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"));
    definition =
        new ToolDefinitionMapper()
            .map(
                plugin.descriptor(),
                new String(
                    BuiltinManifestLoader.resource("input.schema.json"), StandardCharsets.UTF_8));
  }

  public void run(Lease lease) {
    Session session = new Session(lease);
    var previous = ledger.checkpoint(lease);
    List<Message> messages =
        previous
            .map(step -> restore(Payloads.read(step.request(), Checkpoint.class)))
            .orElseGet(() -> List.of(new UserMessage(lease.goal())));
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
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("fixture-v1").build();
          }
        };
    var client = ChatClient.builder(model).defaultAdvisors(advisor).build();
    var options =
        ToolCallingChatOptions.builder()
            .model("fixture-v1")
            .toolCallbacks(session.discoveryCallback())
            .build();
    var response = client.prompt(new Prompt(messages, options)).call().chatResponse();
    if (response.hasToolCalls()) throw new TaskFailure(409, "MODEL_LOOP_INCOMPLETE");
    String summary = response.getResult().getOutput().getText();
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

  private List<Message> restore(Checkpoint checkpoint) {
    if (checkpoint.version() != 1) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
    return checkpoint.messages().stream().map(this::message).toList();
  }

  private Message message(WireMessage message) {
    return switch (message.role()) {
      case "user" -> new UserMessage(message.content());
      case "system" -> new SystemMessage(message.content());
      case "assistant" -> assistant(new Response(1, message.content(), message.calls(), null));
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
    private ExecutionLedger.Step step;
    private List<Call> calls = List.of();
    private boolean hadTools;

    Session(Lease lease) {
      this.lease = lease;
    }

    private ChatResponse call(Prompt prompt) {
      var checkpoint =
          new Checkpoint(
              1,
              "fixture-v1",
              prompt.getInstructions().stream().map(DurableAgent.this::wire).toList(),
              lease.capabilities(),
              lease.installationEpoch());
      step = ledger.prepare(lease, Payloads.json(checkpoint));
      probe.hit("M0", lease.taskId());
      Response response;
      if (step.response() != null) response = Payloads.read(step.response(), Response.class);
      else {
        String dispatch = ledger.dispatchModel(lease, step.id());
        probe.hit("M1", lease.taskId());
        response = request(checkpoint);
        probe.hit("M1_RESPONSE", lease.taskId());
        ledger.modelResponded(
            lease,
            step.id(),
            dispatch,
            Payloads.json(response),
            slots(response),
            response.tokens());
      }
      if (response.version() != 1) throw new TaskFailure(409, "PAYLOAD_VERSION_UNSUPPORTED");
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

    private ToolCallback discoveryCallback() {
      return new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
          return definition;
        }

        @Override
        public String call(String input) {
          throw new IllegalStateException("Unbound callback");
        }
      };
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
          return List.of(definition);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
          var byName = new HashMap<String, List<Call>>();
          for (Call call : calls)
            byName.computeIfAbsent(call.tool(), name -> new ArrayList<>()).add(call);
          List<ToolCallback> callbacks = new ArrayList<>();
          byName.forEach(
              (name, slots) -> {
                if (!definition.name().equals(name))
                  throw new TaskFailure(403, "TOOL_NOT_AUTHORIZED");
                var counter = new AtomicInteger();
                callbacks.add(
                    new ToolCallback() {
                      @Override
                      public ToolDefinition getToolDefinition() {
                        return definition;
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
      try {
        inputSchema.validate(json.readTree(input));
      } catch (IOException | IllegalArgumentException e) {
        throw new TaskFailure(400, "TOOL_INPUT_INVALID");
      }
      probe.hit("T0_BEFORE", lease.taskId());
      var invocation = ledger.admit(lease, step.id(), call);
      if ("SUCCEEDED".equals(invocation.state())) {
        outputSchema.validate(Payloads.read(invocation.output(), JsonNode.class));
        return invocation.output();
      }
      probe.hit("T0", lease.taskId());
      String output;
      try (var reader = new ConfiguredOriginReader(origin, credentials.get(lease.tenant()))) {
        FetchInput args = Payloads.read(input, FetchInput.class);
        var result =
            new WebFetchPlugin(reader, plugin.descriptor())
                .execute(
                    args,
                    new ExecutionContext(
                        invocation.id(), lease.deadline(), () -> ledger.checkpoint(lease)));
        outputSchema.validateObject(result.output());
        output = Payloads.json(result.output());
        if (output.getBytes(StandardCharsets.UTF_8).length > 2097152)
          throw new TaskFailure(409, "TOOL_OUTPUT_TOO_LARGE");
      } catch (RuntimeException error) {
        ledger.uncertain(lease, invocation.id());
        throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
      }
      probe.hit("T1_BEFORE", lease.taskId());
      ledger.toolSucceeded(lease, invocation.id(), output);
      probe.hit("T1", lease.taskId());
      return output;
    }

    private Response request(Checkpoint checkpoint) {
      ObjectNode body = json.createObjectNode();
      body.put("model", "fixture-v1");
      body.put("stream", false);
      body.put("max_tokens", 2048);
      var messages = body.putArray("messages");
      for (WireMessage m : checkpoint.messages()) {
        if ("tool".equals(m.role())) {
          for (ToolReply r : m.replies()) {
            var node = messages.addObject();
            node.put("role", "tool");
            node.put("tool_call_id", r.id());
            node.put("content", r.data());
          }
        } else {
          var node = messages.addObject();
          node.put("role", m.role());
          node.put("content", m.content());
          if (!m.calls().isEmpty())
            for (ToolCall c : m.calls()) {
              var t = node.withArray("tool_calls").addObject();
              t.put("id", c.id());
              t.put("type", "function");
              var f = t.putObject("function");
              f.put("name", c.name());
              f.put("arguments", c.arguments());
            }
        }
      }
      var tool = body.putArray("tools").addObject();
      tool.put("type", "function");
      var function = tool.putObject("function");
      function.put("name", definition.name());
      function.put("description", definition.description());
      try {
        function.set("parameters", json.readTree(definition.inputSchema()));
        Duration remaining = Duration.between(Instant.now(), lease.deadline());
        if (remaining.isNegative() || remaining.isZero())
          throw new TaskFailure(409, "DEADLINE_EXCEEDED");
        Duration timeout =
            remaining.compareTo(Duration.ofSeconds(20)) < 0 ? remaining : Duration.ofSeconds(20);
        var response =
            http.post(
                origin.resolve("/v1/chat/completions"),
                json.writeValueAsBytes(body),
                timeout,
                credentials.get(lease.tenant()));
        if (response.statusCode() != 200 || response.body().length > 1048576)
          throw new IOException("Invalid provider response");
        var root = json.readTree(response.body());
        var choice = root.path("choices").path(0);
        var message = choice.path("message");
        if (!message.isObject()
            || !List.of("stop", "tool_calls").contains(choice.path("finish_reason").asText()))
          throw new IOException("Incomplete provider response");
        List<ToolCall> tools = new ArrayList<>();
        for (var c : message.path("tool_calls")) {
          if (tools.size() >= 20
              || !c.path("id").asText().matches("[A-Za-z0-9_-]{1,128}")
              || !"function".equals(c.path("type").asText())
              || !c.path("function").path("arguments").isTextual())
            throw new IOException("Invalid tool slot");
          tools.add(
              new ToolCall(
                  c.path("id").asText(),
                  "function",
                  c.path("function").path("name").asText(),
                  c.path("function").path("arguments").asText()));
        }
        var usage = root.path("usage").path("total_tokens");
        Integer tokens =
            usage.isIntegralNumber() && usage.canConvertToInt() ? usage.intValue() : null;
        return new Response(1, message.path("content").asText(""), List.copyOf(tools), tokens);
      } catch (IOException e) {
        throw new TaskFailure(502, "MODEL_RESPONSE_UNAVAILABLE");
      }
    }
  }

  @Override
  public void close() {
    http.close();
  }
}
