package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EvaluationMaterialsTest {
  @Test
  void frozenBytesArePackagedAndMatchActualGoalsSourcesAndDraft() throws Exception {
    for (String sample : List.of("W1", "W2", "W3", "W4")) {
      var material = EvaluationMaterials.load(EvaluationPlans.plan("qwen-main-v1"), sample);
      assertEquals(
          Files.readString(Path.of("tools/evaluation/frozen/prompts/" + sample + ".goal.txt")),
          material.goal());
      assertEquals(sample.equals("W3") ? 2 : 1, material.sources().size());
      assertEquals(sample.equals("W4"), material.initialContent() != null);
      assertEquals(
          material, Payloads.read(Payloads.json(material), EvaluationMaterials.Material.class));
    }
  }

  @Test
  void completeActualWireMaterialsRejectEachUnfrozenInputIncludingCachedHistory() throws Exception {
    var material = EvaluationMaterials.load(EvaluationPlans.plan("qwen-main-v1"), "W1");
    var json = new ObjectMapper();
    var body = json.createObjectNode();
    body.set("tools", material.tools());
    var messages = body.putArray("messages");
    String time = "2026-10-16T01:02:03Z";
    messages
        .addObject()
        .put("role", "system")
        .put("content", material.systemTemplate().replace("{{HOST_REPORT_TIME}}", time));
    messages.addObject().put("role", "user").put("content", material.goal());
    var source = material.sources().entrySet().iterator().next();
    var call =
        messages
            .addObject()
            .put("role", "assistant")
            .putArray("tool_calls")
            .addObject()
            .put("id", "source")
            .putObject("function");
    call.put("name", "web_fetch")
        .put("arguments", Payloads.json(java.util.Map.of("url", source.getKey())));
    messages
        .addObject()
        .put("role", "tool")
        .put("tool_call_id", "source")
        .put("content", source.getValue().toString());
    assertDoesNotThrow(() -> EvaluationMaterials.body(material, body.toString(), time));
    List<Consumer<ObjectNode>> mutations =
        List.of(
            b ->
                ((ObjectNode) b.path("messages").get(1))
                    .put("content", "unfrozen-plain-goal-no-C1"),
            b -> ((ObjectNode) b.path("messages").get(0)).put("content", "changed system"),
            b ->
                ((ObjectNode) b.path("tools").get(0).path("function"))
                    .put("description", "changed description"),
            b ->
                ((ObjectNode) b.path("tools").get(0).path("function").path("parameters"))
                    .put("additionalProperties", true),
            b ->
                ((ObjectNode) b.path("messages").get(2).path("tool_calls").get(0).path("function"))
                    .put("arguments", "{\"url\":\"https://fixture.example/unfrozen\"}"),
            b ->
                ((ObjectNode) b.path("messages").get(3))
                    .put(
                        "content",
                        source
                            .getValue()
                            .deepCopy()
                            .toString()
                            .replace("User Datagram", "Altered Datagram")));
    for (var mutate : mutations) {
      var changed = body.deepCopy();
      mutate.accept(changed);
      var failure =
          assertThrows(
              TaskFailure.class,
              () -> EvaluationMaterials.body(material, changed.toString(), time));
      assertEquals("EVALUATION_MATERIAL_MISMATCH", failure.code());
    }
  }
}
