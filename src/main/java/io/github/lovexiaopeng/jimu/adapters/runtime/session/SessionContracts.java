package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Byte-pinned public-v1 contracts, strict untrusted input and deterministic public projection. */
public final class SessionContracts {
  public static final String NAME = "session_event_read";
  private static final JsonMapper JSON = mapper(32), INPUT = mapper(4);
  private static final Pattern TOKENS =
      Pattern.compile("[\\p{IsWhite_Space}\\uFEFF]+|[^\\p{IsWhite_Space}\\uFEFF]+");
  private static final Pattern SENSITIVE =
      Pattern.compile("(?i)(authorization|bearer|token|secret|password|api[_-]?key|env:)");
  private final SchemaContract input, output, event, context, owner;
  private final String schema, description, binding;
  private final ToolDescriptor descriptor;
  private final int version;

  private static JsonMapper mapper(int depth) {
    return JsonMapper.builder(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(depth).build())
                .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
  }

  public SessionContracts() {
    this(1);
  }

  public SessionContracts(int version) {
    this.version = version;
    try {
      byte[] catalogBytes =
          resource(
              version == 1 ? "catalog.json" : "v" + version + "/catalog-v" + version + ".json");
      var catalog = JSON.readTree(catalogBytes);
      var entry = catalog.path("tools").get(0);
      String prefix = version == 1 ? "" : "v" + version + "/";
      String root =
          prefix
              + "session_event_read-"
              + (version == 1 ? "0.1.0" : version == 2 ? "0.2.0" : version == 3 ? "0.3.0" : "0.4.0")
              + "/";
      byte[] in = checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] out = checked(root + "output.schema.json", entry.path("outputSha256").asText());
      checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      description =
          new String(
              checked(root + "description.txt", entry.path("descriptionSha256").asText()),
              StandardCharsets.UTF_8);
      input = new SchemaContract(in);
      output = new SchemaContract(out);
      event =
          new SchemaContract(
              checked(
                  prefix + "event-v" + version + ".schema.json",
                  catalog.path("schemas").path("event-v" + version + ".schema.json").asText()));
      context =
          new SchemaContract(
              checked(
                  prefix + "context-v" + version + ".schema.json",
                  catalog.path("schemas").path("context-v" + version + ".schema.json").asText()));
      owner =
          new SchemaContract(
              checked(
                  prefix + "owner-history-v" + version + ".schema.json",
                  catalog
                      .path("schemas")
                      .path("owner-history-v" + version + ".schema.json")
                      .asText()));
      var template = JSON.readTree(resource(prefix + "examples/binding.json"));
      new SchemaContract(resource(prefix + "task-binding-v" + version + ".schema.json"))
          .validate(template);
      if (!Payloads.hash(catalogBytes).equals(template.path("catalogDigest").asText()))
        throw new IllegalArgumentException("Session catalog pin");
      binding = Payloads.json(template);
      schema = new String(in, StandardCharsets.UTF_8);
      descriptor =
          new ToolDescriptor(
              "jimu.session.event_read",
              version == 1 ? "0.1.0" : version == 2 ? "0.2.0" : version == 3 ? "0.3.0" : "0.4.0",
              NAME,
              "session/" + root + "input.schema.json",
              "session/" + root + "output.schema.json",
              Payloads.hash(out),
              "io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventReadPlugin");
    } catch (IOException e) {
      throw new IllegalStateException("Packaged session contract unavailable", e);
    }
  }

  static byte[] resource(String path) throws IOException {
    try (var stream =
        SessionContracts.class.getClassLoader().getResourceAsStream("session/" + path)) {
      if (stream == null) throw new IOException("Missing session contract");
      return stream.readAllBytes();
    }
  }

  static byte[] checked(String path, String digest) throws IOException {
    byte[] value = resource(path);
    if (!Payloads.hash(value).equals(digest))
      throw new IllegalArgumentException("Session pin mismatch");
    return value;
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public String inputSchema() {
    return schema;
  }

  public String description() {
    return description;
  }

  public String binding() {
    return binding;
  }

  public static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  public static void unicode(String value) {
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
  }

  private static void unicode(JsonNode node) {
    if (node.isTextual()) unicode(node.textValue());
    node.fieldNames().forEachRemaining(SessionContracts::unicode);
    node.forEach(SessionContracts::unicode);
  }

  public JsonNode json(String raw, int max) {
    try {
      unicode(raw);
      if (bytes(raw) > max) throw new IllegalArgumentException("Bytes");
      var node = JSON.readTree(raw);
      if (node == null) throw new IllegalArgumentException("Missing JSON");
      unicode(node);
      return node;
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public JsonNode strictJson(String raw, int max) {
    try {
      unicode(raw);
      if (bytes(raw) > max) throw new IllegalArgumentException("Bytes");
      var node = INPUT.readTree(raw);
      if (node == null) throw new IllegalArgumentException("Missing JSON");
      unicode(node);
      return node;
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public SessionEventPort.Input input(byte[] raw) {
    try {
      return input(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString());
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public SessionEventPort.Input input(String raw) {
    try {
      unicode(raw);
      if (bytes(raw) > 1024) throw new IllegalArgumentException("Input bytes");
      var node = INPUT.readTree(raw);
      if (node == null) throw new IllegalArgumentException("Missing input");
      unicode(node);
      for (String key : List.of("seq", "before", "after"))
        if (node.has(key) && !node.path(key).isIntegralNumber())
          throw new IllegalArgumentException("Integer token");
      input.validate(node);
      if (version == 4) PriorSessionContracts.decimals(raw);
      return new SessionEventPort.Input(
          node.path("seq").longValue(),
          node.has("session_id") ? node.path("session_id").textValue() : null,
          node.path("before").asInt(0),
          node.path("after").asInt(0));
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, "TOOL_INPUT_INVALID");
    }
  }

  public void event(String raw) {
    validate(event, raw, 16384);
  }

  public void output(String raw) {
    validate(output, raw, 32768);
  }

  public void context(String raw) {
    validate(context, raw, 4096);
  }

  public void owner(String raw) {
    validate(owner, raw, 4096);
  }

  private void validate(SchemaContract contract, String raw, int max) {
    if (bytes(raw) > max) throw new TaskFailure(429, "SESSION_HISTORY_LIMIT");
    try {
      contract.validate(json(raw, max));
    } catch (IllegalArgumentException | TaskFailure e) {
      throw new TaskFailure(409, "SESSION_HISTORY_CORRUPT");
    }
  }

  public record PublicText(String content, boolean redacted) {}

  public PublicText publicText(String raw, List<String> sensitive) {
    unicode(raw);
    String value = raw;
    for (String secret :
        sensitive.stream()
            .filter(s -> !s.isEmpty())
            .distinct()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList()) value = value.replace(secret, "[redacted]");
    var parts = new ArrayList<String>();
    var matches = TOKENS.matcher(value);
    while (matches.find()) parts.add(matches.group());
    boolean[] hidden = new boolean[parts.size()];
    for (int i = 0; i < parts.size(); i++) {
      String token = parts.get(i);
      if (token.contains("/") || token.contains("\\") || SENSITIVE.matcher(token).find()) {
        hidden[i] = true;
        for (int j = i + 1; j < parts.size(); j++) {
          if (!whitespace(parts.get(j))) {
            hidden[j] = true;
            break;
          }
        }
      }
    }
    var result = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) result.append(hidden[i] ? "[redacted]" : parts.get(i));
    String text = result.toString();
    if (sensitive.stream().filter(s -> !s.isEmpty()).anyMatch(text::contains))
      throw new TaskFailure(409, "SESSION_EVENT_UNSAFE");
    return new PublicText(text, !raw.equals(text));
  }

  private static boolean whitespace(String text) {
    return text.codePoints()
        .allMatch(
            c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85 || c == 0xfeff);
  }

  public void callId(String value, List<String> sensitive) {
    if (!value.matches("[A-Za-z0-9_-]{1,128}")
        || sensitive.stream().filter(s -> !s.isEmpty()).anyMatch(value::contains))
      throw new TaskFailure(400, "SESSION_EVENT_UNSAFE");
  }

  public static String prefix(String text) {
    int end = text.offsetByCodePoints(0, Math.min(256, text.codePointCount(0, text.length())));
    while (bytes(text.substring(0, end)) > 1024) end = text.offsetByCodePoints(end, -1);
    return text.substring(0, end);
  }
}
