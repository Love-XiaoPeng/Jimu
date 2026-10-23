import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
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
import java.util.regex.Pattern;

/** Architecture-only parser/Schema probe; does not exercise the unimplemented product. */
public final class ContractProbe {
  private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final ObjectMapper INPUT = JsonMapper.builder(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final List<String> passed = new ArrayList<>();
  private static Path root;

  private static SchemaContract schema(String name) throws Exception {
    return new SchemaContract(Files.readAllBytes(root.resolve("contracts/" + name)));
  }

  private static void unicode(JsonNode node) {
    if (node.isTextual() && !StandardCharsets.UTF_8.newEncoder().canEncode(node.textValue()))
      throw new IllegalArgumentException("invalid Unicode scalar value");
    node.fieldNames().forEachRemaining(key -> {
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(key)) throw new IllegalArgumentException();
    });
    node.forEach(ContractProbe::unicode);
  }

  private static void input(byte[] raw, SchemaContract schema) throws Exception {
    if (raw.length > 1024) throw new IllegalArgumentException("input bytes");
    String value = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw)).toString();
    JsonNode node = INPUT.readTree(value);
    if (node == null) throw new IllegalArgumentException("missing JSON");
    unicode(node);
    for (String name : List.of("seq", "before", "after"))
      if (node.has(name) && !node.get(name).isIntegralNumber())
        throw new IllegalArgumentException("non-integral token");
    schema.validate(node);
  }

  private static void input(String raw, SchemaContract schema) throws Exception {
    input(raw.getBytes(StandardCharsets.UTF_8), schema);
  }

  @FunctionalInterface private interface Action { void run() throws Exception; }

  private static void accept(String name, Action action) throws Exception {
    action.run();
    passed.add(name);
  }

  private static void reject(String name, Action action) throws Exception {
    boolean rejected = false;
    try { action.run(); } catch (Exception expected) { rejected = true; }
    if (!rejected) throw new AssertionError("Accepted invalid case: " + name);
    passed.add(name);
  }

  // This is a normative public-v1 example probe, not a claim about production redaction.
  private static String publicText(String raw, List<String> sensitive) {
    String value = raw;
    for (String secret : sensitive.stream().filter(s -> !s.isEmpty())
        .sorted((a,b) -> Integer.compare(b.length(), a.length())).toList())
      value = value.replace(secret, "[redacted]");
    var parts = new ArrayList<String>();
    var matcher = Pattern.compile("(?:[\\p{IsWhite_Space}\\uFEFF]+)|(?:[^\\p{IsWhite_Space}\\uFEFF]+)").matcher(value);
    while (matcher.find()) parts.add(matcher.group());
    boolean[] hidden = new boolean[parts.size()];
    Pattern sensitiveToken = Pattern.compile("(?i)(authorization|bearer|token|secret|password|api[_-]?key|env:)");
    for (int n=0; n<parts.size(); n++) {
      String part = parts.get(n);
      if (part.contains("/") || part.contains("\\") || sensitiveToken.matcher(part).find()) {
        hidden[n] = true;
        for (int next=n+1; next<parts.size(); next++) {
          if (!parts.get(next).codePoints().allMatch(c -> Character.isWhitespace(c)
              || Character.isSpaceChar(c) || c==0x85 || c==0xFEFF)) {
            hidden[next] = true;
            break;
          }
        }
      }
    }
    StringBuilder result = new StringBuilder();
    for (int n=0; n<parts.size(); n++) result.append(hidden[n] ? "[redacted]" : parts.get(n));
    return result.toString();
  }

  public static void main(String[] args) throws Exception {
    root = Path.of(args[0]);
    var in = schema("session_event_read-0.1.0/input.schema.json");
    var out = schema("session_event_read-0.1.0/output.schema.json");
    var event = schema("event-v1.schema.json");
    for (String raw : List.of("{\"seq\":0}", "{\"seq\":9007199254740991}",
        "{\"seq\":1,\"before\":4,\"after\":4}",
        "{\"session_id\":\"00000000-0000-4000-8000-000000000052\",\"seq\":0}"))
      accept("input-valid:"+raw, () -> input(raw,in));
    for (String raw : List.of("{}", "[]", "null", "{\"seq\":-1}", "{\"seq\":9007199254740992}",
        "{\"seq\":9223372036854775808}", "{\"seq\":1.0}", "{\"seq\":1e0}", "{\"seq\":true}",
        "{\"seq\":\"1\"}", "{\"seq\":null}", "{\"seq\":1,\"seq\":2}",
        "{\"seq\":1,\"extra\":0}", "{\"seq\":1} {}", "{\"seq\":1,\"before\":5}",
        "{\"seq\":1,\"after\":-1}", "{\"seq\":1,\"after\":0.5}",
        "{\"seq\":1,\"before\":null}", "{\"seq\":1,\"session_id\":\"\"}",
        "{\"seq\":1,\"session_id\":null}",
        "{\"seq\":1,\"session_id\":\"00000000-0000-4000-8000-00000000005A\"}",
        "{\"seq\":1,\"session_id\":\"\\uD800\"}",
        "{\"seq\":1,\"bad\":{\"a\":{\"b\":{\"c\":{\"d\":1}}}}}"))
      reject("input-invalid:"+raw, () -> input(raw,in));
    reject("input-over-1024-bytes", () -> input("{\"seq\":1}"+" ".repeat(1024),in));
    reject("invalid-utf8", () -> input(new byte[]{'{','"','s','e','q','"',':',(byte)0xC0,(byte)0xAF,'}'},in));

    for (String file : List.of("user-event.json", "response-event.json", "read-receipt.json"))
      accept("event-example:"+file, () -> event.validate(JSON.readTree(root.resolve("contracts/examples/"+file).toFile())));
    accept("read-output-example", () -> out.validate(JSON.readTree(root.resolve("contracts/examples/read-output.json").toFile())));
    for (String id : List.of("C:\\private\\credential", "/private/credential", "https://private.example", "call id")) {
      ObjectNode copy = (ObjectNode) JSON.readTree(root.resolve("contracts/examples/response-event.json").toFile());
      ((ObjectNode)copy.path("data").path("calls").get(0)).put("id", id);
      reject("event-invalid-provider-id:"+id, () -> event.validate(copy));
    }
    for (String name : List.of("binding", "context", "owner-history")) {
      String filename = name.equals("binding") ? "task-binding-v1.schema.json" : name+"-v1.schema.json";
      accept("schema-example:"+name, () -> schema(filename).validate(JSON.readTree(root.resolve("contracts/examples/"+name+".json").toFile())));
    }
    ObjectNode valid = (ObjectNode) JSON.readTree(root.resolve("contracts/examples/read-output.json").toFile());
    for (String kind : List.of("unknown-version", "extra-field", "unknown-event", "oversize-window", "internal-field", "wrong-source")) {
      ObjectNode copy = valid.deepCopy();
      switch(kind) {
        case "unknown-version" -> copy.put("history_version",2);
        case "extra-field" -> copy.put("hidden",true);
        case "unknown-event" -> ((ObjectNode)copy.path("target")).put("type","system.message");
        case "oversize-window" -> { for (int n=0;n<4;n++) copy.withArray("before").add(copy.path("before").get(0).deepCopy()); }
        case "internal-field" -> ((ObjectNode)copy.path("target").path("data")).put("credentialRef","hidden");
        case "wrong-source" -> ((ObjectNode)copy.path("target").path("source")).put("kind","request");
        default -> throw new AssertionError();
      }
      reject("output-invalid:"+kind, () -> out.validate(copy));
    }
    String unicode = "积木 🧱\n第二行";
    accept("unicode-roundtrip", () -> {
      if (!JSON.readTree(JSON.writeValueAsBytes(unicode)).asText().equals(unicode)) throw new AssertionError();
    });
    accept("public-policy-normative-example", () -> {
      String raw = "普通中文 🧱\nBearer SYNTHETIC_CREDENTIAL C:\\private\\x following /private/data next https://example.org/?key=x next DONE";
      String safe = publicText(raw,List.of("SYNTHETIC_CREDENTIAL"));
      if (!safe.equals("普通中文 🧱\n[redacted] [redacted] [redacted] [redacted] [redacted] [redacted] [redacted] [redacted] DONE"))
        throw new AssertionError(safe);
    });
    accept("receipt-has-no-nested-output", () -> {
      JsonNode receipt = JSON.readTree(root.resolve("contracts/examples/read-receipt.json").toFile());
      if (receipt.path("data").has("output") || receipt.path("data").has("target")) throw new AssertionError();
      if (!receipt.path("data").has("output_sha256")) throw new AssertionError();
    });
    Files.createDirectories(root.resolve("evidence"));
    Files.writeString(root.resolve("evidence/schema-cases.json"), JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(java.util.Map.of("scope","Architecture schemas/parser examples only; no product/DB/worker tests", "passed",passed))+"\n");
    System.out.println("PASS " + passed.size() + " architecture Schema/parser/public-policy cases using production SchemaContract.");
  }
}
