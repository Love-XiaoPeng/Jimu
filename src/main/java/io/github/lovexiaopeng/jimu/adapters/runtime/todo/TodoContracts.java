package io.github.lovexiaopeng.jimu.adapters.runtime.todo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

/** Byte-pinned catalog and strict untrusted model JSON boundary. */
public final class TodoContracts {
  public static final String NAME = "todo_write";
  private static final JsonMapper STRICT =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final SchemaContract in, out, view;
  private final String input, description, binding;
  private final ToolDescriptor descriptor;

  public TodoContracts() {
    try {
      byte[] catalogBytes = resource("catalog.json");
      JsonNode catalog = STRICT.readTree(catalogBytes), entry = catalog.path("variants").get(0);
      String root = entry.path("root").asText() + "/";
      byte[] inputBytes = checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] outputBytes =
          checked(root + "output.schema.json", entry.path("outputSha256").asText());
      checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      byte[] descriptionBytes =
          checked(root + "description.txt", entry.path("descriptionSha256").asText());
      checked("task-binding-v1.schema.json", catalog.path("bindingSchemaSha256").asText());
      view =
          new SchemaContract(
              checked("todo-view-v1.schema.json", catalog.path("viewSchemaSha256").asText()));
      in = new SchemaContract(inputBytes);
      out = new SchemaContract(outputBytes);
      input = new String(inputBytes, StandardCharsets.UTF_8);
      description = new String(descriptionBytes, StandardCharsets.UTF_8);
      descriptor =
          new ToolDescriptor(
              "jimu.todo.write",
              "0.1.0",
              NAME,
              root + "input.schema.json",
              root + "output.schema.json",
              Payloads.hash(outputBytes),
              "io.github.lovexiaopeng.jimu.plugins.builtin.todo.TodoWritePlugin");
      var node = STRICT.readTree(resource("examples/binding.json"));
      if (!node.path("catalogDigest").asText().equals(Payloads.hash(catalogBytes)))
        throw new IllegalArgumentException("Todo binding catalog mismatch");
      new SchemaContract(resource("task-binding-v1.schema.json")).validate(node);
      binding = Payloads.json(node);
    } catch (IOException e) {
      throw new IllegalStateException("Packaged todo contract unavailable", e);
    }
  }

  private static byte[] resource(String path) throws IOException {
    try (var stream = TodoContracts.class.getClassLoader().getResourceAsStream("todo/" + path)) {
      if (stream == null) throw new IOException("Missing todo resource");
      return stream.readAllBytes();
    }
  }

  private static byte[] checked(String path, String digest) throws IOException {
    byte[] bytes = resource(path);
    if (!Payloads.hash(bytes).equals(digest))
      throw new IllegalArgumentException("Todo catalog digest mismatch");
    return bytes;
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public String inputSchema() {
    return input;
  }

  public String description() {
    return description;
  }

  public String binding() {
    return binding;
  }

  public static int bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  public static void unicode(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
          throw new TaskFailure(400, "TOOL_INPUT_INVALID");
      } else if (Character.isLowSurrogate(c)) throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  private static boolean whitespace(char c) {
    return c >= 9 && c <= 13
        || c == 0x20
        || c == 0xa0
        || c == 0x1680
        || c >= 0x2000 && c <= 0x200a
        || c == 0x2028
        || c == 0x2029
        || c == 0x202f
        || c == 0x205f
        || c == 0x3000
        || c == 0xfeff;
  }

  private static String trim(String value) {
    int start = 0, end = value.length();
    while (start < end && whitespace(value.charAt(start))) start++;
    while (end > start && whitespace(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  public TodoPort.Result input(String raw) {
    try {
      unicode(raw);
      if (bytes(raw) > 16384) throw new IllegalArgumentException("Input bytes");
      JsonNode node = STRICT.readTree(raw);
      in.validate(node);
      var items = new ArrayList<TodoPort.Item>();
      var seen = new HashSet<String>();
      int pending = 0, active = 0, completed = 0;
      for (var item : node.path("todos")) {
        String content = trim(item.path("content").textValue()),
            status = item.path("status").textValue();
        unicode(content);
        if (content.isEmpty() || bytes(content) > 1024 || !seen.add(content))
          throw new IllegalArgumentException("Content");
        switch (status) {
          case "pending" -> pending++;
          case "in_progress" -> active++;
          case "completed" -> completed++;
          default -> throw new IllegalArgumentException("Status");
        }
        items.add(new TodoPort.Item(content, status));
      }
      if (active > 1 || bytes(Payloads.json(items)) > 8192)
        throw new IllegalArgumentException("List limit");
      return new TodoPort.Result(items, new TodoPort.Counts(pending, active, completed));
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public TodoPort.Result output(String raw) {
    try {
      if (bytes(raw) > 16384) throw new TaskFailure(409, "TOOL_OUTPUT_TOO_LARGE");
      unicode(raw);
      var node = STRICT.readTree(raw);
      out.validate(node);
      var result = input(Payloads.json(java.util.Map.of("todos", node.path("todos"))));
      if (!Payloads.json(result).equals(raw))
        throw new IllegalArgumentException("Noncanonical output");
      return result;
    } catch (TaskFailure e) {
      if (e.code().equals("TOOL_OUTPUT_TOO_LARGE")) throw e;
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    }
  }

  public void view(Object value) {
    view.validate(Payloads.read(Payloads.json(value), JsonNode.class));
  }
}
