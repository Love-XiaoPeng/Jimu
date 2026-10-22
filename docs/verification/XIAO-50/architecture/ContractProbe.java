import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Contract-only probe; normalization candidate is not product implementation. */
public final class ContractProbe {
  private static final ObjectMapper JSON = JsonMapper.builder(
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  static boolean whitespace(int cp) {
    return cp >= 9 && cp <= 13 || cp == 0x20 || cp == 0xA0 || cp == 0x1680
      || cp >= 0x2000 && cp <= 0x200A || cp == 0x2028 || cp == 0x2029
      || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF;
  }

  static String trim(String text) {
    for (int i=0; i<text.length(); i++) {
      char c=text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Unicode");
      } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unicode");
    }
    int a=0,b=text.length();
    while (a<b && whitespace(text.codePointAt(a))) a+=Character.charCount(text.codePointAt(a));
    while (a<b && whitespace(text.codePointBefore(b))) b-=Character.charCount(text.codePointBefore(b));
    return text.substring(a,b);
  }

  static JsonNode normalize(String raw, SchemaContract input, SchemaContract output) throws Exception {
    if (raw.getBytes(StandardCharsets.UTF_8).length>16384) throw new IllegalArgumentException("Input bytes");
    JsonNode parsed=JSON.readTree(raw); input.validate(parsed);
    ArrayNode todos=JSON.createArrayNode(); var seen=new HashSet<String>();
    int pending=0,active=0,completed=0;
    for (JsonNode item:parsed.path("todos")) {
      String content=trim(item.path("content").textValue());
      if (content.isEmpty() || content.getBytes(StandardCharsets.UTF_8).length>1024 || !seen.add(content))
        throw new IllegalArgumentException("Content");
      String status=item.path("status").textValue();
      if (status.equals("pending")) pending++;
      else if (status.equals("in_progress")) active++;
      else completed++;
      todos.addObject().put("content",content).put("status",status);
    }
    if (active>1 || JSON.writeValueAsBytes(todos).length>8192) throw new IllegalArgumentException("List policy");
    ObjectNode value=JSON.createObjectNode(); value.set("todos",todos);
    value.putObject("counts").put("pending",pending).put("inProgress",active).put("completed",completed);
    if (JSON.writeValueAsBytes(value).length>16384) throw new IllegalArgumentException("Output bytes");
    output.validate(value); return value;
  }

  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]); ObjectMapper fixtures=new ObjectMapper();
    int schemas=0,positive=0,negative=0,semantics=0;
    try (var files=Files.walk(root.resolve("contracts"))) {
      for (Path p:files.filter(p->p.toString().endsWith(".schema.json")).toList()) {
        new SchemaContract(Files.readAllBytes(p)); schemas++;
      }
    }
    for (JsonNode c:fixtures.readTree(Files.readAllBytes(root.resolve("evidence/schema-cases.json")))) {
      var contract=new SchemaContract(Files.readAllBytes(root.resolve("contracts").resolve(c.path("schema").asText())));
      boolean valid=true;
      try {contract.validate(c.path("value"));} catch (IllegalArgumentException expected) {valid=false;}
      if (valid!=c.path("valid").asBoolean()) throw new AssertionError(c.path("name").asText());
      if(valid) positive++; else negative++;
    }
    var input=new SchemaContract(Files.readAllBytes(root.resolve("contracts/todo_write-0.1.0/input.schema.json")));
    var output=new SchemaContract(Files.readAllBytes(root.resolve("contracts/todo_write-0.1.0/output.schema.json")));
    for (JsonNode c:fixtures.readTree(Files.readAllBytes(root.resolve("evidence/semantic-cases.json")))) {
      boolean valid=true; JsonNode value=null;
      String raw=c.path("raw").isTextual()?c.path("raw").textValue():fixtures.writeValueAsString(c.path("input"));
      try {value=normalize(raw,input,output);} catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException expected) {valid=false;}
      if(valid!=c.path("valid").asBoolean() || valid && !value.equals(c.path("expected")))
        throw new AssertionError(c.path("name").asText());
      semantics++;
    }
    System.out.printf("PASS baseline SchemaContract: %d schemas, %d positive, %d negative; JVM strict JSON/normalization candidate: %d cases%n",schemas,positive,negative,semantics);
    System.out.println("No product DB, invocation, worker, migration, crash or authorization proof was executed.");
  }
}
