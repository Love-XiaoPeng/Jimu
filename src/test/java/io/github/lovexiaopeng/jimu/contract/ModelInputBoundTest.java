package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolReply;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelInputBounds;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

class ModelInputBoundTest {
  private final OpenAiProtocol protocol = new OpenAiProtocol();
  private static final String PROOF = "test-whole-request-utf8-v1";

  private ModelSnapshot model(boolean stream, ModelSnapshot.InputLimit limit, String url) {
    return new ModelSnapshot(
        2,
        "test",
        1,
        "openai-chat-v1",
        url,
        "local-byte-accounted-v1",
        "local-byte-accounted-v1",
        "none",
        null,
        0,
        "trusted-local-v1",
        2000,
        20000,
        1048576,
        stream,
        512,
        "max_completion_tokens",
        10,
        new ModelSnapshot.Capabilities(
            true, true, true, true, true, true, false, 1000000, "local-test-v1"),
        Map.of(),
        false,
        null,
        limit);
  }

  private ToolDefinition tool(String description) {
    return ToolDefinition.builder()
        .name("read")
        .description(description)
        .inputSchema("{\"type\":\"object\",\"properties\":{\"file_path\":{\"type\":\"string\"}}}")
        .build();
  }

  @Test
  void completeJsonAndSseRequestsUseExactUtf8BoundAt8191Through8193() {
    for (boolean stream : List.of(false, true)) {
      var m = model(stream, new ModelSnapshot.InputLimit(1, 8192, PROOF), "http://127.0.0.1/v1");
      for (int size : List.of(8191, 8192, 8193)) {
        String content = "中文😀";
        String base =
            protocol.body(
                m,
                List.of(new WireMessage("user", content, List.of(), List.of())),
                tool("真实 Schema"));
        String padded =
            protocol.body(
                m,
                List.of(
                    new WireMessage(
                        "user",
                        content + "a".repeat(size - base.getBytes(StandardCharsets.UTF_8).length),
                        List.of(),
                        List.of())),
                tool("真实 Schema"));
        assertEquals(size, padded.getBytes(StandardCharsets.UTF_8).length);
        if (size <= 8192) ModelInputBounds.check(m, padded, true);
        else
          assertEquals(
              "MODEL_INPUT_LIMIT_EXCEEDED",
              assertThrows(TaskFailure.class, () -> ModelInputBounds.check(m, padded, true))
                  .code());
      }
    }
  }

  @Test
  void systemSchemaAssistantArgumentsAndToolResultsAreAllCounted() {
    var history =
        List.of(
            new WireMessage("system", "系统 policy", List.of(), List.of()),
            new WireMessage("user", "goal", List.of(), List.of()),
            new WireMessage(
                "assistant",
                "history",
                List.of(
                    new ToolCall("call-1", "function", "read", "{\"file_path\":\"report.md\"}")),
                List.of()),
            new WireMessage("tool", "", List.of(), List.of(new ToolReply("call-1", "read", "结果"))));
    var base = model(false, new ModelSnapshot.InputLimit(1, 8192, PROOF), "http://127.0.0.1/v1");
    String small = protocol.body(base, history, tool("schema"));
    long size = small.getBytes(StandardCharsets.UTF_8).length;
    var limited = model(false, new ModelSnapshot.InputLimit(1, size, PROOF), base.baseUrl());
    ModelInputBounds.check(limited, small, true);
    for (String token : List.of("系统 policy", "schema", "history", "report.md", "结果")) {
      String larger = small.replace(token, token + "更长😀");
      assertTrue(larger.getBytes(StandardCharsets.UTF_8).length > size);
      assertEquals(
          "MODEL_INPUT_LIMIT_EXCEEDED",
          assertThrows(TaskFailure.class, () -> ModelInputBounds.check(limited, larger, true))
              .code());
    }
  }

  @Test
  void proofTextCannotEnableRealProviderOrNonTestDispatch() {
    for (String url :
        List.of(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "http://localhost/v1",
            "http://192.168.1.1/v1")) {
      var m = model(false, new ModelSnapshot.InputLimit(1, 8192, PROOF), url);
      assertEquals(
          "MODEL_INPUT_BOUND_UNAVAILABLE",
          assertThrows(TaskFailure.class, () -> ModelInputBounds.check(m, "{}", true)).code());
    }
    var local = model(false, new ModelSnapshot.InputLimit(1, 8192, PROOF), "http://127.0.0.1/v1");
    assertThrows(TaskFailure.class, () -> ModelInputBounds.check(local, "{}", false));
    var claim =
        model(
            false, new ModelSnapshot.InputLimit(1, 8192, "operator-says-bounded"), local.baseUrl());
    assertEquals(
        "MODEL_INPUT_BOUND_UNAVAILABLE",
        assertThrows(TaskFailure.class, () -> ModelInputBounds.check(claim, "{}", true)).code());
  }

  @Test
  void optionalPolicyPreservesOldCanonicalSnapshotAndPinsNewPolicy() {
    var legacy = model(false, null, "http://127.0.0.1/v1");
    String old = Payloads.json(legacy);
    assertFalse(old.contains("inputLimit"));
    assertEquals(old, Payloads.json(Payloads.read(old, ModelSnapshot.class)));
    var limited = model(false, new ModelSnapshot.InputLimit(1, 8192, PROOF), legacy.baseUrl());
    String saved = Payloads.json(limited);
    assertEquals(limited, Payloads.read(saved, ModelSnapshot.class));
    assertFalse(Payloads.hash(old).equals(Payloads.hash(saved)));
    ModelInputBounds.check(legacy, "unbounded legacy fixture", false);
  }
}
