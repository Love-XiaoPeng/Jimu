package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;

/** JSON is confined to this boundary. P1 schemas are single-file 2020-12 documents. */
public final class SchemaContract {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Schema schema;

  public SchemaContract(byte[] bytes) throws IOException {
    JsonNode document = mapper.readTree(bytes);
    if (!"https://json-schema.org/draft/2020-12/schema".equals(document.path("$schema").asText())) {
      throw new IllegalArgumentException("Unsupported Schema dialect");
    }
    checkReferences(document);
    var config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    schema =
        SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config))
            .getSchema(document.toString(), InputFormat.JSON);
    schema.initializeValidators();
  }

  private static void checkReferences(JsonNode node) {
    for (String keyword : new String[] {"$ref", "$dynamicRef"}) {
      if (node.has(keyword) && !node.get(keyword).asText().startsWith("#")) {
        throw new IllegalArgumentException(
            "Only references within this packaged Schema are allowed");
      }
    }
    node.elements().forEachRemaining(SchemaContract::checkReferences);
  }

  public void validate(JsonNode value) {
    if (!schema.validate(value.toString(), InputFormat.JSON).isEmpty())
      throw new IllegalArgumentException("Schema validation failed");
  }

  public void validateObject(Object value) {
    validate(mapper.valueToTree(value));
  }
}
