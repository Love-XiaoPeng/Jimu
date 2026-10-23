import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Validates the candidate schemas with Jimu's actual validator, not a trace implementation. */
public final class ContractProbe {
  private static final JsonFactory FACTORY = JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final JsonMapper STRICT = JsonMapper.builder(FACTORY)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> passed = new ArrayList<>();
  private static Path root;
  private static SchemaContract input;
  @FunctionalInterface interface Action { void run() throws Exception; }
  private static void accept(String name, Action action) throws Exception {
    try { action.run(); } catch (Exception e) { throw new IllegalArgumentException(name, e); }
    passed.add(name);
  }
  private static void reject(String name, Action action) throws Exception {
    boolean rejected = false;
    try { action.run(); } catch (Exception expected) { rejected = true; }
    if (!rejected) throw new AssertionError("Accepted invalid case: " + name);
    passed.add(name);
  }
  private static SchemaContract schema(String p) throws Exception {
    return new SchemaContract(Files.readAllBytes(root.resolve("contracts/" + p)));
  }
  private static void unicode(JsonNode n) {
    if (n.isTextual() && !StandardCharsets.UTF_8.newEncoder().canEncode(n.textValue()))
      throw new IllegalArgumentException("Unicode");
    n.fieldNames().forEachRemaining(k -> {
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(k)) throw new IllegalArgumentException();
    });
    n.forEach(ContractProbe::unicode);
  }
  private static void parse(byte[] b) throws Exception {
    if (b.length > 1024) throw new IllegalArgumentException("Bytes");
    String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
    try (var p = FACTORY.createParser(s)) {
      int depth = 0;
      for (JsonToken token; (token = p.nextToken()) != null;) {
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
          if (++depth > 4) throw new IllegalArgumentException("Depth");
        } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
        if (token == JsonToken.VALUE_NUMBER_FLOAT
            || token == JsonToken.VALUE_NUMBER_INT && !p.getText().matches("0|[1-9][0-9]*"))
          throw new IllegalArgumentException("Integer lexeme");
      }
    }
    var n = STRICT.readTree(s);
    if (n == null) throw new IllegalArgumentException();
    unicode(n); input.validate(n);
  }
  private static void parse(String s) throws Exception { parse(s.getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    root = Path.of(args[0]); input = schema("session_event_trace-0.1.0/input.schema.json");
    try (var paths = Files.walk(root.resolve("contracts"))) {
      for (Path p : paths.filter(x -> x.toString().endsWith(".schema.json")).toList())
        accept("compile:" + root.relativize(p), () -> new SchemaContract(Files.readAllBytes(p)));
    }
    for (String s : List.of("{\"seq\":0}", "{\"seq\":9007199254740991}",
        "{\"seq\":1,\"session_id\":\"00000000-0000-4000-8000-000000000054\"}"))
      accept("valid:" + s, () -> parse(s));
    for (String s : List.of("{}", "[]", "null", "{\"seq\":null}", "{\"seq\":false}",
        "{\"seq\":\"1\"}", "{\"seq\":-0}", "{\"seq\":-1}", "{\"seq\":1.0}",
        "{\"seq\":1e0}", "{\"seq\":9007199254740992}", "{\"seq\":0,\"seq\":1}",
        "{\"seq\":0,\"depth\":1}", "{\"seq\":0} {}", "{\"seq\":0,\"session_id\":\"\\uD800\"}",
        "{\"seq\":0,\"session_id\":\"00000000-0000-4000-8000-00000000005A\"}"))
      reject("invalid:" + s, () -> parse(s));
    accept("input-1024", () -> parse("{\"seq\":0}" + " ".repeat(1015)));
    reject("input-1025", () -> parse("{\"seq\":0}" + " ".repeat(1016)));
    reject("invalid-utf8", () -> parse(new byte[] {'{', '"', (byte) 0xc0, (byte) 0xaf, '"', ':', '0', '}'}));
    String[][] examples = {{"user-event", "event-v3"}, {"read-receipt", "event-v3"},
        {"trace-input", "session_event_trace-0.1.0/input"}, {"trace-output", "session_event_trace-0.1.0/output"},
        {"read-output", "session_event_read-0.3.0/output"}, {"search-output", "session_event_search-0.2.0/output"},
        {"context", "context-v3"}, {"owner-history", "owner-history-v3"},
        {"binding", "task-binding-v3"}, {"read-sources", "read-sources"}};
    for (String[] e : examples)
      accept("example:" + e[0], () -> schema(e[1] + ".schema.json")
          .validate(JSON.readTree(root.resolve("contracts/examples/" + e[0] + ".json").toFile())));
    ObjectNode event = (ObjectNode) JSON.readTree(root.resolve("contracts/examples/read-receipt.json").toFile());
    reject("successful-read-no-sources", () -> {
      var copy = event.deepCopy(); copy.putArray("source_event_seqs"); schema("event-v3.schema.json").validate(copy);
    });
    reject("non-read-has-sources", () -> {
      var copy = JSON.readTree(root.resolve("contracts/examples/user-event.json").toFile());
      ((ObjectNode) copy).putArray("source_event_seqs").add(0); schema("event-v3.schema.json").validate(copy);
    });
    reject("duplicate-sources", () -> {
      var copy = event.deepCopy(); copy.putArray("source_event_seqs").add(0).add(0); schema("event-v3.schema.json").validate(copy);
    });
    reject("ten-sources", () -> {
      var copy = event.deepCopy(); var a = copy.putArray("source_event_seqs");
      for (int i = 0; i < 10; i++) a.add(i); schema("event-v3.schema.json").validate(copy);
    });
    ObjectNode out = (ObjectNode) JSON.readTree(root.resolve("contracts/examples/trace-output.json").toFile());
    for (String field : List.of("surface", "replacement_chain", "count", "cursor"))
      reject("undeclared-output:" + field, () -> {
        var copy = out.deepCopy(); copy.put(field, "invented");
        schema("session_event_trace-0.1.0/output.schema.json").validate(copy);
      });
    reject("twenty-one-derived", () -> {
      var copy = out.deepCopy(); var a = copy.putArray("derived_event_seqs");
      for (int i = 1; i <= 21; i++) a.add(i);
      schema("session_event_trace-0.1.0/output.schema.json").validate(copy);
    });
    Files.writeString(root.resolve("evidence/schema-cases.json"), JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(java.util.Map.of("passed", passed, "count", passed.size(),
            "scope", "candidate schema/parser probe only; no product provider or MySQL runtime")) + "\n");
    System.out.println("PASS " + passed.size() + " candidate schema/parser checks");
  }
}
