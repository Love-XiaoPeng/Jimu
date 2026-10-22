import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** Framework experiment only: no Jimu task, DB, model HTTP or plugin implementation. */
public final class SuspensionProbe {
  static final class Suspended extends RuntimeException {}

  static ToolCallback callback(String name, Runnable action) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
      }
      public String call(String input) { action.run(); return "{}"; }
    };
  }

  public static void main(String[] args) {
    for (boolean stream : List.of(false, true)) {
      AtomicInteger models = new AtomicInteger(), before = new AtomicInteger();
      AtomicInteger ask = new AtomicInteger(), after = new AtomicInteger();
      Suspended expected = new Suspended();
      var response = new ChatResponse(List.of(new Generation(
          AssistantMessage.builder().toolCalls(List.of(
              new AssistantMessage.ToolCall("a", "function", "before", "{}"),
              new AssistantMessage.ToolCall("b", "function", "ask", "{}"),
              new AssistantMessage.ToolCall("c", "function", "after", "{}")
          )).build())));
      ChatModel model = new ChatModel() {
        public ChatResponse call(Prompt prompt) {
          if (models.incrementAndGet() > 1) throw new AssertionError("unexpected next model call");
          return response;
        }
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().model("local-probe").build(); }
      };
      var options = ToolCallingChatOptions.builder().model("local-probe").toolCallbacks(
          callback("before", () -> before.incrementAndGet()),
          callback("ask", () -> { ask.incrementAndGet(); throw expected; }),
          callback("after", () -> after.incrementAndGet())
      ).build();
      var manager = DefaultToolCallingManager.builder().resolutionFallbackEnabled(false)
          .maxTotalToolCalls(20).maxCallsPerTool(20).build();
      var client = ChatClient.builder(model).defaultAdvisors(
          ToolCallingAdvisor.builder().toolCallingManager(manager).build()).build();
      boolean propagated = false;
      try {
        var request = client.prompt(new Prompt("probe", options));
        if (stream) request.stream().chatResponse().blockLast(Duration.ofSeconds(5));
        else request.call().chatResponse();
      } catch (Suspended actual) {
        if (actual != expected) throw new AssertionError("marker identity changed");
        propagated = true;
      }
      if (!propagated || models.get() != 1 || before.get() != 1 || ask.get() != 1 || after.get() != 0)
        throw new AssertionError("suspension escaped invariant");
      System.out.println("PASS " + (stream ? "stream" : "call")
          + ": same suspension marker, models=1 prior=1 ask=1 later=0; no error tool reply or next model call");
    }
  }
}
