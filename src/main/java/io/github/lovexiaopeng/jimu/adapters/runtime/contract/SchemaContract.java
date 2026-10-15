package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;

/** JSON is confined to this boundary. P1 schemas are single-file 2020-12 documents. */
public final class SchemaContract {
  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchema schema;

  public SchemaContract(byte[] bytes) throws IOException {
    JsonNode document = mapper.readTree(bytes);
    if (!"https://json-schema.org/draft/2020-12/schema".equals(document.path("$schema").asText())) {
      throw new IllegalArgumentException("Unsupported Schema dialect");
    }
    checkReferences(document);
    var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document, config);
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
    if (!schema.validate(value).isEmpty())
      throw new IllegalArgumentException("Schema validation failed");
  }

  public void validateObject(Object value) {
    validate(mapper.valueToTree(value));
  }
}
