package io.github.lovexiaopeng.jimu.adapters.runtime.directory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.WorkingDirectoryPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Packaged byte pins and strict JSON boundary for the new profile only. */
public final class DirectoryContracts {
  public static final String NAME = "working_directory";
  public static final String PROFILE = "local-logical-directory-v1";
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final SchemaContract in, out, view, bindingSchema;
  private final String input, description, catalogDigest;
  private final ObjectNode template;
  private final ToolDescriptor descriptor;

  public DirectoryContracts() {
    try {
      byte[] catalogBytes = resource("catalog.json");
      var catalog = JSON.readTree(catalogBytes);
      catalogDigest = Payloads.hash(catalogBytes);
      String root = "working_directory-0.1.0/";
      var entry = catalog.path("tools").get(0);
      byte[] inputBytes = checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] outputBytes =
          checked(root + "output.schema.json", entry.path("outputSha256").asText());
      checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      description =
          new String(
              checked(root + "description.txt", entry.path("descriptionSha256").asText()),
              StandardCharsets.UTF_8);
      for (int index = 1; index < catalog.path("tools").size(); index++) {
        var old = catalog.path("tools").get(index);
        String oldRoot = "plugins/builtin/" + old.path("name").asText() + "/";
        for (String file :
            java.util.List.of("input.schema.json", "output.schema.json", "plugin.yaml")) {
          String pin =
              file.startsWith("input")
                  ? "inputSha256"
                  : file.startsWith("output") ? "outputSha256" : "manifestSha256";
          try (var stream =
              DirectoryContracts.class.getClassLoader().getResourceAsStream(oldRoot + file)) {
            if (stream == null
                || !Payloads.hash(stream.readAllBytes()).equals(old.path(pin).asText()))
              throw new IllegalArgumentException("Directory file tool pin mismatch");
          }
        }
      }
      in = new SchemaContract(inputBytes);
      out = new SchemaContract(outputBytes);
      view = new SchemaContract(resource("directory-view-v1.schema.json"));
      bindingSchema = new SchemaContract(resource("task-binding-v1.schema.json"));
      template = (ObjectNode) JSON.readTree(resource("examples/binding.json"));
      bindingSchema.validate(template);
      if (!template.path("catalogDigest").asText().equals(catalogDigest))
        throw new IllegalArgumentException("Directory catalog mismatch");
      input = new String(inputBytes, StandardCharsets.UTF_8);
      descriptor =
          new ToolDescriptor(
              "jimu.workspace.working_directory",
              "0.1.0",
              NAME,
              "directory/" + root + "input.schema.json",
              "directory/" + root + "output.schema.json",
              Payloads.hash(outputBytes),
              WorkingDirectoryPlugin.class.getName());
    } catch (IOException e) {
      throw new IllegalStateException("Packaged directory contract unavailable", e);
    }
  }

  private static byte[] resource(String path) throws IOException {
    try (var stream =
        DirectoryContracts.class.getClassLoader().getResourceAsStream("directory/" + path)) {
      if (stream == null) throw new IOException("Missing directory contract");
      return stream.readAllBytes();
    }
  }

  private static byte[] checked(String path, String digest) throws IOException {
    byte[] bytes = resource(path);
    if (!Payloads.hash(bytes).equals(digest))
      throw new IllegalArgumentException("Directory pin mismatch");
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

  public String binding(String namespace) {
    ObjectNode node = template.deepCopy();
    node.put("namespace", namespace);
    bindingSchema.validate(node);
    return Payloads.json(node);
  }

  public void binding(String raw, String namespace) {
    if (!binding(namespace).equals(raw))
      throw new TaskFailure(409, "DIRECTORY_BINDING_UNSUPPORTED");
  }

  public JsonNode json(String raw, int maxBytes) {
    try {
      DirectoryPaths.unicode(raw);
      if (raw.getBytes(StandardCharsets.UTF_8).length > maxBytes)
        throw new IllegalArgumentException("Input size");
      JsonNode node = JSON.readTree(raw);
      if (node == null) throw new IllegalArgumentException("Missing JSON value");
      validateUnicode(node);
      return node;
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public record Context(String kind, int version, String cwd, long revision) {}

  public String context(String cwd, long revision) {
    return Payloads.json(new Context("working_directory_context", 1, cwd, revision));
  }

  private static void validateUnicode(JsonNode node) {
    if (node.isTextual()) DirectoryPaths.unicode(node.textValue());
    if (node.isObject()) node.fieldNames().forEachRemaining(DirectoryPaths::unicode);
    node.forEach(DirectoryContracts::validateUnicode);
  }

  public WorkingDirectoryPlugin.Input input(String raw) {
    try {
      var node = json(raw, 4096);
      in.validate(node);
      return new WorkingDirectoryPlugin.Input(node.has("cd") ? node.path("cd").textValue() : null);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public WorkingDirectoryPort.Result output(String raw) {
    try {
      var node = json(raw, 4096);
      out.validate(node);
      String cwd = node.path("cwd").textValue();
      if (!DirectoryPaths.resolve("/", cwd, true).equals(cwd))
        throw new IllegalArgumentException("Cwd");
      var result = new WorkingDirectoryPort.Result(cwd);
      if (!Payloads.json(result).equals(raw))
        throw new IllegalArgumentException("Canonical output");
      return result;
    } catch (RuntimeException e) {
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    }
  }

  public void view(Object value) {
    view.validate(Payloads.read(Payloads.json(value), JsonNode.class));
  }
}
