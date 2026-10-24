package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrozenEvaluationMaterialsTest {
  @Test
  void deliveredBytesAndHostTemplateRemainFrozen() throws Exception {
    Path root = Path.of("tools/evaluation/frozen");
    var json = new ObjectMapper();
    for (var item : json.readTree(root.resolve("MANIFEST.json").toFile())) {
      Path file = root.resolve(item.path("path").asText()).normalize();
      assertTrue(file.startsWith(root));
      byte[] bytes = Files.readAllBytes(file);
      assertEquals(item.path("bytes").asLong(), bytes.length, file.toString());
      assertEquals(item.path("sha256").asText(), Payloads.hash(bytes), file.toString());
    }
    Instant now = Instant.parse("2026-10-16T01:02:03.123456Z");
    assertEquals(
        Files.readString(root.resolve("prompts/host-system.template.txt"))
            .stripTrailing()
            .replace("{{HOST_REPORT_TIME}}", now.toString()),
        new ReportPolicy().instruction(new ReportContext(now, List.of())));
  }
}
