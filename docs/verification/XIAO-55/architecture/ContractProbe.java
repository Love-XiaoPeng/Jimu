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

/** Real Jimu Schema validator probe; this is not a product implementation or runtime test. */
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
    try { action.run(); } catch (Exception failure) { throw new IllegalArgumentException(name, failure); }
    passed.add(name);
  }
  private static void reject(String name, Action action) throws Exception {
    boolean rejected=false;
    try { action.run(); } catch (Exception expected) { rejected=true; }
    if (!rejected) throw new AssertionError("Accepted invalid case: "+name);
    passed.add(name);
  }
  private static SchemaContract schema(String p) throws Exception {
    return new SchemaContract(Files.readAllBytes(root.resolve("contracts/"+p+".schema.json")));
  }
  private static void unicode(JsonNode n) {
    if (n.isTextual()&&!StandardCharsets.UTF_8.newEncoder().canEncode(n.textValue()))
      throw new IllegalArgumentException("Unicode");
    n.fieldNames().forEachRemaining(k->{
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(k)) throw new IllegalArgumentException();
    });
    n.forEach(ContractProbe::unicode);
  }
  private static void parse(byte[] body) throws Exception {
    if (body.length>4096) throw new IllegalArgumentException("Bytes");
    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
    try (var parser=FACTORY.createParser(text)) {
      int depth=0;
      for (JsonToken token;(token=parser.nextToken())!=null;) {
        if (token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY) {
          if (++depth>4) throw new IllegalArgumentException("Depth");
        } else if (token==JsonToken.END_OBJECT||token==JsonToken.END_ARRAY) depth--;
        if (token==JsonToken.VALUE_NUMBER_FLOAT
            ||token==JsonToken.VALUE_NUMBER_INT&&!parser.getText().matches("0|[1-9][0-9]*"))
          throw new IllegalArgumentException("Number lexeme");
      }
    }
    var node=STRICT.readTree(text);
    if (node==null) throw new IllegalArgumentException();
    unicode(node); input.validate(node);
    String query=node.path("query").textValue();
    if (query.codePointCount(0,query.length())>256
        ||query.getBytes(StandardCharsets.UTF_8).length>1024)
      throw new IllegalArgumentException("Query bytes/codepoints");
  }
  private static void parse(String s) throws Exception { parse(s.getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    root=Path.of(args[0]); input=schema("session_search-0.1.0/input");
    try (var paths=Files.walk(root.resolve("contracts"))) {
      for (Path p:paths.filter(x->x.toString().endsWith(".schema.json")).toList())
        accept("compile:"+root.relativize(p),()->new SchemaContract(Files.readAllBytes(p)));
    }
    for (String s:List.of("{\"query\":\"积木 🧱\"}","{\"query\":\"OR [a].*\",\"event_seq_to\":9007199254740991}",
        "{\"query\":\"q\",\"event_types\":[\"user.message\",\"model.response\"]}"))
      accept("valid:"+s,()->parse(s));
    for (String s:List.of("{}","[]","null","{\"query\":null}","{\"query\":false}",
        "{\"query\":\"\"}","{\"query\":\"q\",\"event_seq_to\":-0}","{\"query\":\"q\",\"event_seq_to\":-1}",
        "{\"query\":\"q\",\"event_seq_to\":1.0}","{\"query\":\"q\",\"event_seq_to\":1e0}",
        "{\"query\":\"q\",\"event_seq_to\":9007199254740992}","{\"query\":\"q\",\"query\":\"z\"}",
        "{\"query\":\"q\",\"limit\":1}","{\"query\":\"q\",\"cursor\":\"x\"}","{\"query\":\"q\"} {}",
        "{\"query\":\"\\uD800\"}","{\"query\":\"\\u0000\"}","{\"query\":\"q\",\"session_ids\":[]}",
        "{\"query\":\"q\",\"event_types\":[\"user.message\",\"user.message\"]}",
        "{\"query\":\"q\",\"availability\":[\"unavailable\"]}","{\"query\":\"q\",\"event_surfaces\":[\"invented\"]}"))
      reject("invalid:"+s,()->parse(s));
    accept("syntax-only-unsupported-false",()->parse("{\"query\":\"q\",\"include_root_sessions\":false}"));
    accept("syntax-only-unsupported-live",()->parse("{\"query\":\"q\",\"availability\":[\"live\"]}"));
    accept("query-256-codepoints-1024-UTF8",()->parse("{\"query\":\""+"🧱".repeat(256)+"\"}"));
    reject("query-257-codepoints",()->parse("{\"query\":\""+"q".repeat(257)+"\"}"));
    String minimal="{\"query\":\"q\"}";
    accept("input-4096",()->parse(minimal+" ".repeat(4096-minimal.length())));
    reject("input-4097",()->parse(minimal+" ".repeat(4097-minimal.length())));
    reject("invalid-UTF8",()->parse(new byte[]{'{','"',(byte)0xc0,(byte)0xaf,'"',':','0','}'}));
    String[][] examples={{"admission","admission-input"},{"admission-empty-scope","admission-input"},
        {"admission-output","admission-output"},{"scope","authority-scope"},{"binding","task-binding-v4"},
        {"user-event","event-v4"},{"read-receipt","event-v4"},{"read-refs","read-refs"},
        {"context","context-v4"},{"owner-history","owner-history-v4"},
        {"read-output","session_event_read-0.4.0/output"},{"search-input","session_search-0.1.0/input"},
        {"search-output","session_search-0.1.0/output"},{"empty-output","session_search-0.1.0/output"}};
    for (String[] pair:examples)
      accept("example:"+pair[0],()->schema(pair[1]).validate(JSON.readTree(root.resolve("contracts/examples/"+pair[0]+".json").toFile())));
    ObjectNode out=(ObjectNode)JSON.readTree(root.resolve("contracts/examples/search-output.json").toFile());
    for (String key:List.of("surface","parent","availability","total_count","candidate_count","cursor"))
      reject("undeclared-output:"+key,()->{var copy=out.deepCopy();copy.put(key,"invented");schema("session_search-0.1.0/output").validate(copy);});
    reject("five-representatives",()->{var copy=out.deepCopy();var a=copy.putArray("items");for(int i=0;i<5;i++)a.add(out.path("items").get(0));schema("session_search-0.1.0/output").validate(copy);});
    reject("old-history-output",()->{var copy=out.deepCopy();((ObjectNode)copy.path("items").get(0)).put("history_version",3);schema("session_search-0.1.0/output").validate(copy);});
    ObjectNode event=(ObjectNode)JSON.readTree(root.resolve("contracts/examples/read-receipt.json").toFile());
    reject("successful-read-no-refs",()->{var copy=event.deepCopy();copy.putArray("source_event_refs");schema("event-v4").validate(copy);});
    reject("old-local-seqs-not-cross-refs",()->{var copy=event.deepCopy();copy.putArray("source_event_seqs").add(0);schema("event-v4").validate(copy);});
    reject("read-no-target-session",()->{var copy=event.deepCopy();((ObjectNode)copy.get("data")).remove("target_session_id");schema("event-v4").validate(copy);});
    reject("caller-authored-tenant",()->{var copy=JSON.readTree(root.resolve("contracts/examples/admission.json").toFile());((ObjectNode)copy).put("tenant_id","invented");schema("admission-input").validate(copy);});
    Files.writeString(root.resolve("evidence/schema-cases.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of("count",passed.size(),"passed",passed,"scope","candidate schema/parser checks only; unsupported field syntax acceptance is not product support; no MySQL/provider/runtime validation"))+"\n");
    System.out.println("PASS "+passed.size()+" candidate schema/parser checks");
  }
}
