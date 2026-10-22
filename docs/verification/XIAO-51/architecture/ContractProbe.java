import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Architecture boundary/third-party probe. No new product implementation is exercised. */
public final class ContractProbe {
  private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private static void parse(String raw, SchemaContract schema) throws Exception {
    if (raw.getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException();
    JsonNode value = JSON.readTree(raw);
    schema.validate(value);
    if (value.has("cd")) {
      String cd = value.path("cd").textValue();
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(cd) || cd.length() > 512
          || cd.getBytes(StandardCharsets.UTF_8).length > 2048) throw new IllegalArgumentException();
    }
  }

  private static final class Unconfirmed extends RuntimeException {}

  private static int manager(boolean fail) {
    var actual = new ArrayList<String>();
    var names = List.of("working_directory", "read");
    var callbacks = names.stream().map(name -> (ToolCallback) new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description("Architecture ordering probe")
            .inputSchema("{\"type\":\"object\"}").build();
      }
      @Override public String call(String input) {
        actual.add(name + ":" + input);
        if (fail && actual.size() == 2) throw new Unconfirmed();
        return "{}";
      }
    }).toList();
    var calls = List.of(
        new AssistantMessage.ToolCall("cd-1", "function", "working_directory", "{\"cd\":\"a\"}"),
        new AssistantMessage.ToolCall("read-1", "function", "read", "{\"file_path\":\"x\"}"),
        new AssistantMessage.ToolCall("cd-2", "function", "working_directory", "{\"cd\":\"../b\"}"),
        new AssistantMessage.ToolCall("read-2", "function", "read", "{\"file_path\":\"x\"}"));
    var options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    var response = new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("").toolCalls(calls).build())));
    boolean thrown = false;
    try {
      DefaultToolCallingManager.builder().resolutionFallbackEnabled(false).build()
          .executeToolCalls(new Prompt("Architecture probe", options), response);
    } catch (Unconfirmed expected) { thrown = true; }
    if (thrown != fail) throw new AssertionError("Unconfirmed propagation");
    var expected = calls.stream().limit(fail ? 2 : 4).map(c -> c.name() + ":" + c.arguments()).toList();
    if (!actual.equals(expected)) throw new AssertionError("Array order: " + actual);
    return actual.size();
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    int schemas = 0, valid = 0, invalid = 0;
    try (var files = Files.walk(root.resolve("contracts"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".schema.json")).toList()) {
        new SchemaContract(Files.readAllBytes(file)); schemas++;
      }
    }
    for (JsonNode c : JSON.readTree(Files.readAllBytes(root.resolve("evidence/schema-cases.json")))) {
      var schema = new SchemaContract(Files.readAllBytes(root.resolve("contracts").resolve(c.path("schema").textValue())));
      boolean accepted = true;
      try { schema.validate(c.path("value")); } catch (IllegalArgumentException rejected) { accepted = false; }
      if (accepted != c.path("valid").booleanValue()) throw new AssertionError(c.path("name"));
      if (accepted) valid++; else invalid++;
    }
    var input = new SchemaContract(Files.readAllBytes(root.resolve("contracts/working_directory-0.1.0/input.schema.json")));
    var bad = List.of("{\"cd\":\"a\",\"cd\":\"b\"}", "{} {}", "{\"cd\":\"\\uD800\"}",
        "{\"cd\":\"\\uDC00\"}", "{\"cd\":null}", "{\"cd\":\"\"}", "{\"sessionId\":\"other\"}",
        "{\"cd\":\"" + "😀".repeat(257) + "\"}");
    for (String raw : bad) {
      boolean rejected = false;
      try { parse(raw, input); } catch (Exception expected) { rejected = true; }
      if (!rejected) throw new AssertionError("Strict JSON/Unicode accepted invalid fixture");
    }
    parse("{}", input); parse("{\"cd\":\"/子目录/😀\"}", input);
    boolean badUtf8 = false;
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(new byte[]{(byte)0xc0, (byte)0xaf}));
    } catch (java.nio.charset.CharacterCodingException expected) { badUtf8 = true; }
    if (!badUtf8) throw new AssertionError("UTF8 replacement decoding");
    int normal = manager(false), stopped = manager(true);
    System.out.printf("PASS actual baseline SchemaContract: %d schemas, %d valid / %d invalid fixtures%n", schemas, valid, invalid);
    System.out.printf("PASS strict boundary candidate: %d rejected JSON/Unicode fixtures, 2 valid, malformed UTF8 rejected%n", bad.size());
    System.out.printf("PASS actual Spring AI manager: %d alternating slots in response-array order; exception stops after %d, original RuntimeException escapes%n", normal, stopped);
    System.out.println("NOT RUN: product directory code, database/DDL, call/stream full loop, locks, crash recovery, auth, cleanup, upgrade or business acceptance.");
  }
}
