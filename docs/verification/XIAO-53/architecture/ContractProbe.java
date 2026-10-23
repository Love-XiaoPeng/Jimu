import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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

/** Architecture schema/parser compatibility probe, not an implementation of the search provider. */
public final class ContractProbe {
  private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final List<String> passed = new ArrayList<>();
  private static Path root;
  private static SchemaContract input;
  @FunctionalInterface interface Action { void run() throws Exception; }
  private static void accept(String name, Action action) throws Exception {
    action.run(); passed.add(name);
  }
  private static void reject(String name, Action action) throws Exception {
    boolean rejected=false;
    try { action.run(); } catch(Exception expected) { rejected=true; }
    if(!rejected) throw new AssertionError("Accepted invalid case: "+name);
    passed.add(name);
  }
  private static SchemaContract schema(String file) throws Exception {
    return new SchemaContract(Files.readAllBytes(root.resolve("contracts/"+file)));
  }
  private static void unicode(JsonNode value) {
    if(value.isTextual()&&!StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue()))
      throw new IllegalArgumentException("Unicode scalar");
    value.fieldNames().forEachRemaining(name->{
      if(!StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw new IllegalArgumentException();
    });
    value.forEach(ContractProbe::unicode);
  }
  private static void parse(byte[] raw) throws Exception {
    if(raw.length>2048) throw new IllegalArgumentException("Bytes");
    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
    JsonNode value=JSON.readTree(text);
    if(value==null) throw new IllegalArgumentException();
    unicode(value);
    for(String field:List.of("seq_from","seq_to"))
      if(value.has(field)&&!value.path(field).isIntegralNumber()) throw new IllegalArgumentException();
    input.validate(value);
  }
  private static void parse(String raw) throws Exception { parse(raw.getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    root=Path.of(args[0]);input=schema("session_event_search-0.1.0/input.schema.json");
    try(var files=Files.walk(root.resolve("contracts"))) {
      for(Path file:files.filter(x->x.toString().endsWith(".schema.json")).toList())
        accept("compile-schema:"+file.getFileName(),()->new SchemaContract(Files.readAllBytes(file)));
    }
    for(String raw:List.of("{\"query\":\"积木 🧱\"}","{\"query\":\"OR .* (x)\",\"seq_to\":9007199254740991}",
        "{\"query\":\"x\",\"event_types\":[\"step.started\",\"user.message\"]}",
        "{\"query\":\"x\",\"time_from\":\"2026-10-23T09:00:00.000001+08:00\"}"))
      accept("valid-input:"+raw,()->parse(raw));
    for(String raw:List.of("{}","[]","null","{\"query\":null}","{\"query\":1}",
        "{\"query\":\"\"}","{\"query\":\"x\\u0000y\"}","{\"query\":\"x\",\"query\":\"y\"}",
        "{\"query\":\"x\",\"cursor\":\"x\"}","{\"query\":\"x\"} {}",
        "{\"query\":\"x\",\"seq_from\":1.0}","{\"query\":\"x\",\"seq_to\":1e0}",
        "{\"query\":\"x\",\"seq_to\":true}","{\"query\":\"x\",\"seq_to\":\"1\"}",
        "{\"query\":\"x\",\"seq_to\":-1}","{\"query\":\"x\",\"seq_to\":9007199254740992}",
        "{\"query\":\"x\",\"event_types\":[]}","{\"query\":\"x\",\"event_types\":[\"user.message\",\"user.message\"]}",
        "{\"query\":\"x\",\"event_types\":[\"user/message\"]}","{\"query\":\"x\",\"surfaces\":[]}",
        "{\"query\":\"x\",\"surfaces\":[\"hidden\"]}","{\"query\":\"x\",\"surfaces\":[\"current\",\"current\"]}",
        "{\"query\":\"x\",\"session_id\":\"00000000-0000-4000-8000-00000000005A\"}",
        "{\"query\":\"\\uD800\"}","{\"query\":\"x\",\"time_from\":\"2026-10-23T00:00Z\"}",
        "{\"query\":\"x\",\"time_from\":\"2026-10-23T00:00:00.1234567Z\"}"))
      reject("invalid-input:"+raw,()->parse(raw));
    reject("query-over-256-codepoints",()->parse("{\"query\":\""+"🧱".repeat(257)+"\"}"));
    reject("input-over-2048-bytes",()->parse("{\"query\":\"x\"}"+" ".repeat(2048)));
    reject("invalid-utf8",()->parse(new byte[]{'{','"','q','u','e','r','y','"',':','"',(byte)0xc0,(byte)0xaf,'"','}'}));
    // The schema deliberately retains valid upstream surfaces. Runtime MUST reject them as unsupported.
    accept("surface-wire-shape-retained-runtime-unsupported",()->parse("{\"query\":\"x\",\"surfaces\":[\"current\"]}"));
    var ordinary=new com.fasterxml.jackson.databind.ObjectMapper();
    String[][] examples={{"user-event","event-v2"},{"model-event","event-v2"},
      {"search-output","session_event_search-0.1.0/output"},{"empty-output","session_event_search-0.1.0/output"},
      {"read-output","session_event_read-0.2.0/output"},{"binding","task-binding-v2"},
      {"context","context-v2"},{"owner-history","owner-history-v2"}};
    for(String[] example:examples) accept("example:"+example[0],()->schema(example[1]+".schema.json")
        .validate(ordinary.readTree(Files.readAllBytes(root.resolve("contracts/examples/"+example[0]+".json")))));
    var output=schema("session_event_search-0.1.0/output.schema.json");
    // Read output contains a nested event beyond the input parser's depth; use an ordinary mapper for examples.
    ObjectNode valid=(ObjectNode)ordinary.readTree(root.resolve("contracts/examples/search-output.json").toFile());
    for(String field:List.of("history_version","surface","count","oversize","hidden")) {
      ObjectNode copy=valid.deepCopy();
      switch(field) {
        case "history_version"->copy.put("history_version",1);
        case "surface"->((ObjectNode)copy.path("items").get(0)).put("surface","current");
        case "count"->copy.put("returned_count",17);
        case "oversize"->{for(int n=0;n<16;n++)copy.withArray("items").add(copy.path("items").get(0));}
        case "hidden"->((ObjectNode)copy.path("items").get(0)).put("source_digest","hidden");
      }
      reject("invalid-output:"+field,()->output.validate(copy));
    }
    var v1=new SchemaContract(Files.readAllBytes(Path.of("src/main/resources/session/event-v1.schema.json")));
    var v1Read=new SchemaContract(Files.readAllBytes(Path.of("src/main/resources/session/session_event_read-0.1.0/output.schema.json")));
    reject("v1-event-rejects-v2",()->v1.validate(ordinary.readTree(root.resolve("contracts/examples/model-event.json").toFile())));
    reject("v1-read-rejects-v2",()->v1Read.validate(ordinary.readTree(root.resolve("contracts/examples/read-output.json").toFile())));
    accept("v1-original-example-still-valid",()->v1Read.validate(ordinary.readTree(Path.of("src/main/resources/session/examples/first-read-output.json").toFile())));
    Files.createDirectories(root.resolve("evidence"));
    Files.writeString(root.resolve("evidence/schema-cases.json"),ordinary.writerWithDefaultPrettyPrinter()
      .writeValueAsString(java.util.Map.of("scope","Architecture schema/strict-parser shapes only. No product search, time/filter semantics, HTTP/DB/worker or business verification.","passed",passed))+"\n");
    System.out.println("PASS "+passed.size()+" architecture Schema/parser/compatibility cases.");
  }
}
