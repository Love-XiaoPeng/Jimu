package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Strict Chat Completions wire boundary, independent of the single advisor loop. */
public final class OpenAiProtocol {
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public String body(ModelSnapshot p, List<WireMessage> history, ToolDefinition definition) {
    return body(p, history, List.of(definition));
  }

  public String body(ModelSnapshot p, List<WireMessage> history, List<ToolDefinition> definitions) {
    var body = json.createObjectNode();
    body.put("model", p.model());
    body.put("stream", p.stream());
    if (BusinessPolicies.STRATEGY.equals(p.adapter())) {
      body.putObject("thinking").put("type", "disabled");
    } else body.put("n", 1);
    body.put(p.outputLimitField(), p.maxOutputTokens() - p.outputMarginTokens());
    if (p.capabilities().parallelToolField()) body.put("parallel_tool_calls", false);
    if (p.stream() && p.capabilities().usageStream())
      body.putObject("stream_options").put("include_usage", true);
    p.providerOptions().forEach(body::put);
    var messages = body.putArray("messages");
    for (var m : history) {
      if ("tool".equals(m.role())) {
        for (var r : m.replies()) {
          var node = messages.addObject();
          node.put("role", "tool");
          node.put("tool_call_id", r.id());
          node.put("content", r.data());
        }
      } else {
        var node = messages.addObject();
        node.put("role", m.role());
        node.put("content", m.content());
        for (var c : m.calls()) {
          var call = node.withArray("tool_calls").addObject();
          call.put("id", c.id());
          call.put("type", "function");
          call.putObject("function").put("name", c.name()).put("arguments", c.arguments());
        }
      }
    }
    var tools = body.putArray("tools");
    try {
      for (var definition : definitions) {
        var function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", definition.name());
        function.put("description", definition.description());
        function.set("parameters", json.readTree(definition.inputSchema()));
      }
      return json.writeValueAsString(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid tool schema", e);
    }
  }

  public Response parse(byte[] bytes, boolean stream, List<String> authorizedTools)
      throws IOException {
    ModelUsage usage = billing(bytes, stream);
    try {
      return executable(bytes, stream, authorizedTools);
    } catch (IOException invalid) {
      throw new InvalidResponse(usage);
    }
  }

  /** Verified billing facts survive rejection of output, including a stream missing DONE. */
  public static final class InvalidResponse extends IOException {
    private final ModelUsage usage;

    InvalidResponse(ModelUsage usage) {
      super("Non-executable model response");
      this.usage = usage;
    }

    public ModelUsage usage() {
      return usage;
    }
  }

  private ModelUsage billing(byte[] bytes, boolean stream) throws IOException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    if (!stream) return usage(json.readTree(text));
    var root = json.createObjectNode();
    // Only complete SSE events supply facts; a truncated final event is never parsed.
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    int end = normalized.lastIndexOf("\n\n");
    if (end < 0) return usage(root);
    for (String event : normalized.substring(0, end).split("\n\n")) {
      var data = new StringBuilder();
      for (String line : event.split("\n")) {
        if (!line.startsWith("data:")) continue;
        if (!data.isEmpty()) data.append('\n');
        data.append(line.substring(5).stripLeading());
      }
      if (data.isEmpty()) continue;
      if ("[DONE]".contentEquals(data)) break;
      var chunk = json.readTree(data.toString());
      if (chunk == null) throw new IOException("Invalid billing event");
      for (String field : List.of("usage", "id", "model")) {
        if (!chunk.hasNonNull(field)) continue;
        if (root.has(field) && !root.get(field).equals(chunk.get(field)))
          throw new IOException("Conflicting billing metadata");
        root.set(field, chunk.get(field));
      }
      if (chunk.path("choices").isArray() && chunk.path("choices").size() == 1) {
        var finish = chunk.path("choices").get(0).get("finish_reason");
        if (finish != null && !finish.isNull()) root.set("finish_reason", finish);
      }
    }
    return usage(root);
  }

  private Response executable(byte[] bytes, boolean stream, List<String> authorizedTools)
      throws IOException {
    String utf8;
    try {
      utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new IOException("Invalid model UTF-8");
    }
    var root = stream ? aggregate(utf8) : json.readTree(utf8);
    if (root == null
        || root.has("error")
        || !root.path("choices").isArray()
        || root.path("choices").size() != 1) throw new IOException("Invalid model response");
    var choice = root.path("choices").get(0);
    var message = choice.path("message");
    String finish = choice.path("finish_reason").asText();
    if (!message.isObject()
        || message.hasNonNull("refusal")
        || !List.of("stop", "tool_calls").contains(finish))
      throw new IOException("Incomplete model response");
    var calls = new ArrayList<ToolCall>();
    var ids = new HashSet<String>();
    var wireCalls = message.path("tool_calls");
    if (!wireCalls.isMissingNode() && !wireCalls.isArray()) throw new IOException("Invalid calls");
    for (var c : wireCalls) {
      String id = c.path("id").asText();
      var function = c.path("function");
      var arguments = function.path("arguments");
      if (calls.size() >= 20
          || !id.matches("[A-Za-z0-9_-]{1,128}")
          || !ids.add(id)
          || !"function".equals(c.path("type").asText())
          || !authorizedTools.contains(function.path("name").asText())
          || !arguments.isTextual()
          || arguments.asText().getBytes(StandardCharsets.UTF_8).length > 16384)
        throw new IOException("Invalid tool slot");
      var parsed = json.readTree(arguments.asText());
      if (parsed == null || !parsed.isObject()) throw new IOException("Invalid tool arguments");
      calls.add(new ToolCall(id, "function", function.path("name").asText(), arguments.asText()));
    }
    if ("tool_calls".equals(finish) == calls.isEmpty())
      throw new IOException("Conflicting finish reason");
    var usage = usage(root);
    Long total = usage.totalTokens();
    Integer tokens = total != null && total <= Integer.MAX_VALUE ? total.intValue() : null;
    return new Response(2, message.path("content").asText(""), List.copyOf(calls), tokens, usage);
  }

  private static ModelUsage usage(JsonNode root) throws IOException {
    if (root == null) throw new IOException("Missing response");
    var u = root.path("usage");
    Long input = count(u, "prompt_tokens"),
        output = count(u, "completion_tokens"),
        total = count(u, "total_tokens");
    if (input != null
        && output != null
        && total != null
        && java.math.BigInteger.valueOf(input)
                .add(java.math.BigInteger.valueOf(output))
                .compareTo(java.math.BigInteger.valueOf(total))
            != 0) throw new IOException("Conflicting usage");
    Long cached = count(u.path("prompt_tokens_details"), "cached_tokens");
    Long deepseekCached = count(u, "prompt_cache_hit_tokens");
    if (cached != null && deepseekCached != null && !cached.equals(deepseekCached))
      throw new IOException("Conflicting cached usage");
    return new ModelUsage(
        input,
        output,
        total,
        cached == null ? deepseekCached : cached,
        count(u.path("completion_tokens_details"), "reasoning_tokens"),
        safeId(root, "id"),
        safeId(root, "model"),
        root.has("finish_reason")
            ? safeId(root, "finish_reason")
            : root.path("choices").isArray() && root.path("choices").size() == 1
                ? safeId(root.path("choices").get(0), "finish_reason")
                : null);
  }

  private static String safeId(JsonNode node, String field) throws IOException {
    if (!node.hasNonNull(field)) return null;
    if (!node.path(field).isTextual()
        || !node.path(field).asText().matches("[A-Za-z0-9_.:/-]{1,160}"))
      throw new IOException("Invalid response identity");
    return node.path(field).asText();
  }

  private static Long count(JsonNode node, String name) throws IOException {
    var value = node.path(name);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
      throw new IOException("Invalid usage");
    return value.longValue();
  }

  private JsonNode aggregate(String sse) throws IOException {
    var root = json.createObjectNode();
    var choice = root.putArray("choices").addObject();
    var message = choice.putObject("message");
    var slots = new TreeMap<Integer, StringBuilder[]>();
    var text = new StringBuilder();
    boolean done = false, finished = false;
    String finish = null;
    // SSE data lines form an event; UTF-8 is decoded after the bounded exchange, so TTFT is
    // UNKNOWN.
    for (String event : sse.replace("\r\n", "\n").replace('\r', '\n').split("\n\n")) {
      var data = new StringBuilder();
      for (String line : event.split("\n")) {
        if (line.startsWith("data:")) {
          if (!data.isEmpty()) data.append('\n');
          String value = line.substring(5);
          data.append(value.startsWith(" ") ? value.substring(1) : value);
        }
      }
      if (data.isEmpty()) continue;
      if (done) throw new IOException("Data after stream completion");
      if ("[DONE]".contentEquals(data)) {
        done = true;
        continue;
      }
      var chunk = json.readTree(data.toString());
      if (chunk == null || chunk.has("error") || !chunk.path("choices").isArray())
        throw new IOException("Invalid stream chunk");
      if (chunk.hasNonNull("usage")) {
        if (root.has("usage") && !root.get("usage").equals(chunk.get("usage")))
          throw new IOException("Conflicting usage");
        root.set("usage", chunk.get("usage"));
      }
      for (String field : List.of("id", "model")) {
        if (chunk.hasNonNull(field)) {
          if (root.has(field) && !root.get(field).equals(chunk.get(field)))
            throw new IOException("Conflicting stream identity");
          root.set(field, chunk.get(field));
        }
      }
      if (chunk.path("choices").isEmpty()) continue;
      if (chunk.path("choices").size() != 1 || finished)
        throw new IOException("Conflicting stream choices");
      var c = chunk.path("choices").get(0);
      if (!c.path("index").isInt() || c.path("index").intValue() != 0)
        throw new IOException("Invalid choice index");
      var delta = c.path("delta");
      if (delta.hasNonNull("refusal")) throw new IOException("Model refusal");
      if (delta.path("content").isTextual()) text.append(delta.path("content").asText());
      for (var call : delta.path("tool_calls")) {
        if (!call.path("index").isInt()) throw new IOException("Invalid stream slot");
        int index = call.path("index").intValue();
        if (index < 0 || index >= 20 || !slots.containsKey(index) && index != slots.size())
          throw new IOException("Non-contiguous stream slot");
        var parts =
            slots.computeIfAbsent(
                index,
                i ->
                    new StringBuilder[] {
                      new StringBuilder(), new StringBuilder(), new StringBuilder()
                    });
        if (call.hasNonNull("type") && !"function".equals(call.path("type").asText()))
          throw new IOException("Invalid tool type");
        append(parts[0], call.path("id"));
        append(parts[1], call.path("function").path("name"));
        append(parts[2], call.path("function").path("arguments"));
      }
      if (c.hasNonNull("finish_reason")) {
        finish = c.path("finish_reason").asText();
        finished = true;
      }
    }
    if (!done || !finished) throw new IOException("Incomplete stream");
    choice.put("finish_reason", finish);
    message.put("content", text.toString());
    var calls = message.putArray("tool_calls");
    for (var parts : slots.values()) {
      var call = calls.addObject().put("id", parts[0].toString()).put("type", "function");
      call.putObject("function")
          .put("name", parts[1].toString())
          .put("arguments", parts[2].toString());
    }
    return root;
  }

  private static void append(StringBuilder target, JsonNode value) throws IOException {
    if (value.isMissingNode() || value.isNull()) return;
    if (!value.isTextual()) throw new IOException("Invalid stream fragment");
    target.append(value.asText());
  }
}
