package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol;
import io.github.lovexiaopeng.jimu.adapters.runtime.http.ProviderHttp;
import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

class ModelProtocolTest {
  private final OpenAiProtocol protocol = new OpenAiProtocol();

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String result(String message, String reason) {
    return "{\"choices\":[{\"message\":" + message + ",\"finish_reason\":\"" + reason + "\"}]}";
  }

  @Test
  void workspaceV3ArgumentsReachTextByteBoundaryWithoutWeakeningLegacyTools() throws Exception {
    var mapper = new ObjectMapper();
    for (String tool : List.of("edit", "glob", "grep")) {
      String input = "{\"value\":\"" + "x".repeat(65523) + "\"}";
      assertEquals(65535, utf8(input).length);
      var message = mapper.createObjectNode();
      var call = message.putArray("tool_calls").addObject();
      call.put("id", "p4").put("type", "function");
      call.putObject("function").put("name", tool).put("arguments", input);
      assertEquals(
          input,
          protocol
              .parse(utf8(result(message.toString(), "tool_calls")), false, List.of(tool))
              .calls()
              .getFirst()
              .arguments());
      call.withObject("function").put("arguments", input + " ");
      assertThrows(
          IOException.class,
          () ->
              protocol.parse(utf8(result(message.toString(), "tool_calls")), false, List.of(tool)));
      call.withObject("function").put("name", "read").put("arguments", input);
      assertThrows(
          IOException.class,
          () ->
              protocol.parse(
                  utf8(result(message.toString(), "tool_calls")), false, List.of("read")));
    }
  }

  @Test
  void usageAbsenceIsUnknownAndComponentUsageIsNotInvented() throws Exception {
    String base = result("{\"content\":\"好\"}", "stop");
    var absent = protocol.parse(utf8(base), false, List.of("web_fetch"));
    assertNull(absent.tokens());
    assertNull(absent.usage().inputTokens());
    var totalOnly =
        protocol.parse(
            utf8(base.substring(0, base.length() - 1) + ",\"usage\":{\"total_tokens\":8}}"),
            false,
            List.of());
    assertEquals(8, totalOnly.tokens());
    assertNull(totalOnly.usage().inputTokens());
    assertNull(totalOnly.usage().outputTokens());
    assertThrows(
        IOException.class,
        () ->
            protocol.parse(
                utf8(base.substring(0, base.length() - 1) + ",\"usage\":{\"total_tokens\":-1}}"),
                false,
                List.of()));
  }

  @Test
  void partialInvalidDuplicateAndUnauthorizedToolsNeverBecomeExecutable() {
    String call =
        "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"web_fetch\",\"arguments\":\"{}\"}}";
    assertThrows(
        IOException.class,
        () ->
            protocol.parse(
                utf8(result("{\"tool_calls\":[" + call + "," + call + "]}", "tool_calls")),
                false,
                List.of("web_fetch")));
    assertThrows(
        IOException.class,
        () ->
            protocol.parse(
                utf8(result("{\"tool_calls\":[" + call + "]}", "tool_calls")),
                false,
                List.of("read")));
    assertThrows(
        IOException.class,
        () ->
            protocol.parse(
                utf8(result("{\"tool_calls\":[" + call.replace("{}", "{") + "]}", "tool_calls")),
                false,
                List.of("web_fetch")));
    for (String reason : List.of("length", "content_filter", "unknown", "tool_calls"))
      assertThrows(
          IOException.class,
          () ->
              protocol.parse(utf8(result("{\"content\":\"partial\"}", reason)), false, List.of()));
    assertThrows(
        IOException.class, () -> protocol.parse(new byte[] {(byte) 0xc3}, false, List.of()));
  }

  @Test
  void sseUsageAfterFinishAndEmptyChoicesArePreserved() throws Exception {
    String stream =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"中\"}}]}\r\n\r\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n"
            + "data: [DONE]\n\n";
    var response = protocol.parse(utf8(stream), true, List.of());
    assertEquals("中", response.content());
    assertEquals(10, response.usage().inputTokens());
    assertEquals(2, response.usage().outputTokens());
    assertThrows(
        IOException.class,
        () -> protocol.parse(utf8(stream.replace("data: [DONE]\n\n", "")), true, List.of()));
    assertThrows(
        IOException.class, () -> protocol.parse(utf8(stream + "data: {}\n\n"), true, List.of()));
  }

  @Test
  void rejectedOutputRetainsVerifiedBillingFactsForCallAndTruncatedStream() throws Exception {
    String usage =
        "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":1000,\"total_tokens\":1100}";
    for (String finish : List.of("length", "content_filter")) {
      String base = result("{\"content\":\"partial\"}", finish);
      var failure =
          assertThrows(
              OpenAiProtocol.InvalidResponse.class,
              () ->
                  protocol.parse(
                      utf8(base.substring(0, base.length() - 1) + "," + usage + "}"),
                      false,
                      List.of()));
      assertEquals(100, failure.usage().inputTokens());
      assertEquals(1000, failure.usage().outputTokens());
      assertEquals(finish, failure.usage().finishReason());
    }
    String stream =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],"
            + usage
            + "}\n\n";
    var failure =
        assertThrows(
            OpenAiProtocol.InvalidResponse.class,
            () -> protocol.parse(utf8(stream + "data: {\"unfinished\":"), true, List.of()));
    assertEquals(1100, failure.usage().totalTokens());
    assertEquals("stop", failure.usage().finishReason());
    assertThrows(
        IOException.class,
        () -> protocol.parse(utf8(stream.replace("1000", "-1")), true, List.of()));
  }

  @Test
  void providerOptionsOutputBoundAndUsageRequestAreExplicit() throws Exception {
    var profile =
        new ModelSnapshot(
            2,
            "test",
            1,
            "openai-chat-v1",
            "http://localhost/v1",
            "test-model",
            "v1",
            "none",
            null,
            0,
            "trusted-local-v1",
            2000,
            20000,
            1048576,
            true,
            512,
            "max_completion_tokens",
            10,
            new ModelSnapshot.Capabilities(true, true, true, true, true, true, false, 8192, "test"),
            Map.of(),
            false,
            null,
            null);
    String body =
        protocol.body(
            profile,
            List.of(new WireMessage("user", "hello", List.of(), List.of())),
            ToolDefinition.builder()
                .name("web_fetch")
                .description("fetch")
                .inputSchema("{\"type\":\"object\"}")
                .build());
    var root = new ObjectMapper().readTree(body);
    assertEquals(502, root.path("max_completion_tokens").intValue());
    assertEquals(1, root.path("n").intValue());
    assertTrue(root.path("stream_options").path("include_usage").booleanValue());
    assertFalse(root.has("parallel_tool_calls"));
    assertFalse(root.has("enable_thinking"));
    assertFalse(root.has("extra_body"));
  }

  @Test
  void prefixAndOptionalAuthorizationAreObservedAtActualHttpBoundary() throws Exception {
    var authorization = new AtomicReference<String>();
    var path = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(utf8("{}"));
          exchange.close();
        });
    server.start();
    try (var http = new ProviderHttp()) {
      var uri =
          URI.create(
              "http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/compatible-mode/v1/chat/completions");
      http.post(uri, utf8("{}"), Duration.ofSeconds(2), null, Duration.ofSeconds(1), 1024);
      assertEquals("/compatible-mode/v1/chat/completions", path.get());
      assertNull(authorization.get());
      http.post(
          uri, utf8("{}"), Duration.ofSeconds(2), "synthetic-test", Duration.ofSeconds(1), 1024);
      assertEquals("Bearer synthetic-test", authorization.get());
      for (String invalid : List.of("synthetic\u0000", "synthetic\n", "含非法头字符")) {
        var error =
            assertThrows(
                TaskFailure.class,
                () ->
                    http.post(
                        uri,
                        utf8("{}"),
                        Duration.ofSeconds(2),
                        invalid,
                        Duration.ofSeconds(1),
                        1024));
        assertEquals("CREDENTIAL_UNAVAILABLE", error.code());
        assertFalse(error.getMessage().contains(invalid));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void moneyRoundsUpUsingFullPrecisionAndNeverTreatsUnknownAsZero() {
    var price = new ModelSnapshot.Price("test-v1", "CNY", 2000000, 8000000, 1, 8192, "synthetic");
    assertEquals(20481, ModelCosts.amount(price, 8192, 512));
    assertEquals(3, ModelCosts.amount(price, 1, 0));
    assertEquals(1, ModelCosts.amount(price, 0, 0));
  }
}
