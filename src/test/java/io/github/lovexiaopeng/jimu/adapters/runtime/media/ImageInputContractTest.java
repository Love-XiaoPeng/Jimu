package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageInputContractTest {
  @Test
  void canonicalBase64AndByteBoundAreEnforcedBeforeAllocation() {
    for (String value : List.of("", "AA", "AA=", "AA===", "AB==", "AA==\n", "____", "AA A"))
      assertThrows(TaskFailure.class, () -> ImageInputs.decode(value));
    for (int n : List.of(1048575, 1048576))
      assertEquals(n, ImageInputs.decode(Base64.getEncoder().encodeToString(new byte[n])).length);
    assertThrows(
        TaskFailure.class,
        () -> ImageInputs.decode(Base64.getEncoder().encodeToString(new byte[1048577])));
  }

  @Test
  void pathsAreExactTaskLogicalNamesWithNoAuthorityFields() {
    for (String path :
        List.of(
            "/tmp/a.png",
            "C:/a.png",
            "\\\\host\\a.png",
            "inputs/images/../a.png",
            "inputs/images//a.png",
            "inputs/images/a.png/",
            "https://example/a.png",
            "inputs/images/./a.png")) assertThrows(TaskFailure.class, () -> TaskImages.path(path));
    assertEquals("inputs/images/中文%20.png", TaskImages.path("inputs/images/中文%20.png"));
    var first = new TaskImages.Input("inputs/images/a", "AA==");
    assertThrows(TaskFailure.class, () -> ImageInputs.describe(List.of(first, first)));
    assertThrows(
        TaskFailure.class,
        () ->
            ImageInputs.describe(
                List.of(first, new TaskImages.Input("inputs/images/a/b", "AA=="))));
    assertThrows(TaskFailure.class, () -> ImageInputs.describe(List.of()));
  }

  @Test
  void oldRequestAndWirePayloadsRemainByteIdentical() {
    var request =
        new Tasks.Request("old", List.of("read"), List.of(), new Tasks.Budget(180, 8, 20, null));
    String expected =
        "{\"budget\":{\"deadlineSeconds\":180,\"maxCostMicros\":null,\"maxModelRequests\":8,\"maxToolInvocations\":20},\"capabilityIds\":[\"read\"],\"goal\":\"old\",\"resourceIds\":[]}";
    assertEquals(expected, Payloads.json(request));
    assertEquals(request, Payloads.read(expected, Tasks.Request.class));
    var wire = new DurableAgent.WireMessage("user", "old", List.of(), List.of());
    assertEquals(
        "{\"calls\":[],\"content\":\"old\",\"replies\":[],\"role\":\"user\"}", Payloads.json(wire));
    assertFalse(Payloads.json(request).contains("images"));
  }
}
