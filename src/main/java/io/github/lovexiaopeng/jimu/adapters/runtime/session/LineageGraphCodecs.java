package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One immutable internal codec extension; independent of the frozen task catalog. */
final class LineageGraphCodecs {
  static final String ID = "jimu.lineage.graph.refs.v2";
  private static final String DESCRIPTOR_SHA256 =
      "3dec462a8d689ce99ab4d8f94eb78e557821aea0da148425fd2ca18a8eb809e2";
  private static final String SCHEMA = "storage/lineage-graph-refs-v2.schema.json";
  final String schemaSha256;
  final JsonNode version;

  LineageGraphCodecs(SessionContracts json) throws IOException {
    var descriptor =
        json.json(
            new String(
                SessionContracts.checked("storage/lineage-graph-codecs-v1.json", DESCRIPTOR_SHA256),
                StandardCharsets.UTF_8),
            4096);
    if (!descriptor.path("id").asText().equals("jimu.lineage.graph.codecs.v1")
        || descriptor.path("version").asInt() != 1
        || descriptor.path("json_codec_version").asInt() != 2
        || !descriptor.path("codec_id").asText().equals(ID)
        || !descriptor.path("schema_resource").asText().equals(SCHEMA)
        || !descriptor.path("semantics").asText().equals("fresh-standalone-genesis-v1"))
      throw new IllegalArgumentException("Lineage codec descriptor");
    schemaSha256 = descriptor.path("schema_sha256").asText();
    byte[] schema = SessionContracts.checked(SCHEMA, schemaSha256);
    // Compile the precise pinned schema at startup, including local references.
    new SchemaContract(schema);
    version =
        json.json(new String(schema, StandardCharsets.UTF_8), 16384)
            .path("properties")
            .path("version");
  }
}
