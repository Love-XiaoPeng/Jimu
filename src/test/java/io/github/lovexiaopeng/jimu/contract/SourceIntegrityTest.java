package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class SourceIntegrityTest {
  @Test
  void allAuthoritativeInputsAndPackagedSchemaRetainExactBytes() throws Exception {
    int checked = 0;
    for (String directory : new String[] {"docs/baseline", "docs/adr"}) {
      var sources =
          new ObjectMapper().readTree(Files.readAllBytes(Path.of(directory, "sources.json")));
      for (var source : sources.path("files")) {
        byte[] bytes = Files.readAllBytes(Path.of(directory, source.path("file").asText()));
        assertEquals(source.path("bytes").asInt(), bytes.length);
        assertEquals(
            source.path("sha256").asText(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        checked++;
      }
    }
    assertEquals(13, checked);
    assertArrayEquals(
        Files.readAllBytes(Path.of("docs/baseline/XIAO-33-web-fetch-output-v0.2.0.schema.json")),
        BuiltinManifestLoader.resource("output.schema.json"));
  }
}
