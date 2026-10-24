package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 与准确 Git 基线比对冻结材料；不修补历史 sources.json 的已知摘要失败。 */
class RefactoringFrozenInputsTest {
  @Test
  void allOriginalResourcesMigrationsAndEvidenceKeepTheirBaselineBytes() throws Exception {
    var baseline =
        new ObjectMapper()
            .readTree(
                Files.readAllBytes(
                    Path.of("docs/verification/XIAO-59/evidence/baseline-files.json")));
    assertEquals("620d5e0e5bfe7b5da530f10997936466159ba34f", baseline.path("commit").asText());
    int checked = 0;
    for (var file : baseline.path("files")) {
      String name = file.path("path").asText();
      if (!name.matches(
          "^(src/main/resources/|docs/(baseline|adr|verification)/|tools/evaluation/frozen/).*$"))
        continue;
      byte[] bytes = Files.readAllBytes(Path.of(name));
      assertEquals(file.path("bytes").asInt(), bytes.length, name);
      assertEquals(file.path("sha256").asText(), Payloads.hash(bytes), name);
      checked++;
    }
    assertEquals(924, checked);
  }
}
