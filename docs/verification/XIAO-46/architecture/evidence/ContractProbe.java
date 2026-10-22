import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;

public class ContractProbe {
  private static int cases;
  private static Schema load(String relative) throws Exception {
    var config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
        builder -> builder.schemaRegistryConfig(config))
        .getSchema(Files.readString(Path.of("handoff/contracts/" + relative)), InputFormat.JSON);
    schema.initializeValidators();
    return schema;
  }
  private static void check(Schema schema, String input, boolean expected, String name) {
    boolean valid = schema.validate(input, InputFormat.JSON).isEmpty();
    if (valid != expected) throw new IllegalStateException(name + ": expected=" + expected);
    cases++;
    System.out.println(name + ": PASS");
  }
  public static void main(String[] args) throws Exception {
    try (var paths = Files.walk(Path.of("handoff/contracts"))) {
      for (var file : paths.filter(p -> p.toString().endsWith(".schema.json")).toList()) {
        load(Path.of("handoff/contracts").relativize(file).toString());
      }
    }
    var read = load("read_image/input.schema.json");
    check(read, "{\"file_path\":\"inputs/images/example.png\"}", true, "read-image-required-input");
    check(read, "{}", false, "read-image-missing-path");
    check(read, "{\"file_path\":\"a\",\"tenant\":\"other\"}", false, "read-image-foreign-scope-field");
    var compose = load("compose_report-0.2.0/input.schema.json");
    String imageSegment = "{\"sourceType\":\"image\",\"kind\":\"text\",\"text\":\"a red square\",\"sourceRef\":\"image:00000000-0000-0000-0000-000000000001\"}";
    String draft = "{\"file_path\":\"report.md\",\"draft\":{\"version\":2,\"subject\":\"test\",\"summary\":[" + imageSegment + "],\"conclusions\":[" + imageSegment + "],\"limitations\":[]}}";
    check(compose, draft, true, "compose-v2-image-shape");
    check(compose, draft.replace("\"version\":2", "\"version\":1"), false, "compose-v1-rejected-by-v2-schema");
    check(compose, draft.replace("\"sourceType\":\"image\"", "\"sourceType\":\"web\""), false, "web-evidence-still-required");
    check(compose, draft.replace("\"kind\":\"text\"", "\"kind\":\"endpoint\""), false, "image-endpoint-forbidden");
    check(compose, draft.replace("\"text\":\"a red square\"", "\"text\":\"a red square\",\"evidence\":\"pretend quote\""), false, "image-quote-forbidden");
    var task = load("task-images-field.schema.json");
    check(task, "[{\"filePath\":\"inputs/images/a.png\",\"dataBase64\":\"AA==\"}]", true, "upload-json-shape-only");
    check(task, "[{\"filePath\":\"inputs/images/a.png\",\"dataBase64\":\"AA==\",\"url\":\"https://example.test/a\"}]", false, "upload-url-field-forbidden");
    System.out.println("schemas=6 cases=" + cases + " all=PASS; semantic authorization/decoding/product not executed");
  }
}
