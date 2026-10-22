import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Runs the unmodified baseline Jimu validator; checks only packaged contract schemas and examples. */
public final class ContractProbe {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    ObjectMapper mapper = new ObjectMapper();
    int schemas = 0, positive = 0, negative = 0;
    try (var files = Files.walk(root.resolve("contracts"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".schema.json")).toList()) {
        new SchemaContract(Files.readAllBytes(file)); schemas++;
      }
    }
    for (var item : mapper.readTree(Files.readString(root.resolve("evidence/schema-cases.json")))) {
      boolean valid = true;
      var schema = new SchemaContract(Files.readAllBytes(root.resolve("contracts").resolve(item.path("schema").asText())));
      try { schema.validate(item.path("value")); }
      catch (IllegalArgumentException expected) { valid = false; }
      if (valid != item.path("valid").asBoolean()) throw new AssertionError(item.path("name").asText());
      if (valid) positive++; else negative++;
    }
    System.out.println("PASS unmodified Jimu SchemaContract: " + schemas + " schemas, " + positive + " positive, " + negative + " negative");
  }
}
