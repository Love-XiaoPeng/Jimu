package io.github.lovexiaopeng.jimu.adapters.runtime.interaction;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.question.api.QuestionPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import io.github.lovexiaopeng.jimu.plugin.api.QuestionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort;
import io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, bounded wire boundary shared by model input and owner answer API. */
public final class QuestionContracts {
  public record Binding(
      int version,
      String profile,
      String mode,
      int defaultTimeoutSeconds,
      String catalogDigest,
      int maxBatches,
      int maxQuestions,
      int maxWaitSeconds,
      int maxTaskBytes) {}

  private static final JsonMapper STRICT =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(16).build())
                  .build())
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  public static final String NAME = ToolCapability.ASK_USER_QUESTION;

  public record Contract(
      ToolDescriptor descriptor,
      String input,
      String description,
      SchemaContract in,
      SchemaContract out) {}

  private final Map<String, Contract> contracts = new LinkedHashMap<>();
  private final JsonNode catalog;
  private final String catalogDigest;
  private final SchemaContract answerSchema;

  public QuestionContracts() {
    try {
      byte[] bytes = resource("catalog.json");
      catalogDigest = Payloads.hash(bytes);
      catalog = STRICT.readTree(bytes);
      answerSchema = new SchemaContract(resource("answer-request-v1.schema.json"));
      for (var entry : catalog.path("variants")) {
        String version = entry.path("version").asText(), root = entry.path("root").asText() + "/";
        byte[] input = resource(root + "input.schema.json"),
            output = resource(root + "output.schema.json"),
            manifest = resource(root + "plugin.yaml");
        if (!Payloads.hash(input).equals(entry.path("inputSha256").asText())
            || !Payloads.hash(output).equals(entry.path("outputSha256").asText())
            || !Payloads.hash(manifest).equals(entry.path("manifestSha256").asText()))
          throw new IllegalArgumentException("Question contract digest mismatch");
        contracts.put(
            version,
            new Contract(
                new ToolDescriptor(
                    "jimu.interaction.ask-user",
                    version,
                    NAME,
                    root + "input.schema.json",
                    root + "output.schema.json",
                    Payloads.hash(output),
                    "io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin"),
                new String(input, StandardCharsets.UTF_8),
                new String(resource(root + "description.txt"), StandardCharsets.UTF_8),
                new SchemaContract(input),
                new SchemaContract(output)));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Packaged question contract unavailable", e);
    }
  }

  private byte[] resource(String path) throws IOException {
    try (var stream =
        QuestionContracts.class.getClassLoader().getResourceAsStream("interaction/" + path)) {
      if (stream == null) throw new IOException("Missing question contract " + path);
      return stream.readAllBytes();
    }
  }

  public Contract contract(String version) {
    var value = contracts.get(version);
    if (value == null) throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
    return value;
  }

  public String binding(String version) {
    contract(version);
    return Payloads.json(
        new Binding(
            1,
            "local-durable-questions-v1",
            mode(version),
            QuestionPolicyV1.DEFAULT_TIMEOUT_SECONDS,
            catalogDigest,
            QuestionPolicyV1.BATCH_QUESTIONS,
            QuestionPolicyV1.OPTIONS,
            QuestionPolicyV1.MAX_TIMEOUT_SECONDS,
            QuestionPolicyV1.STORAGE_BYTES));
  }

  public String mode(String version) {
    return version.equals("0.1.0") ? "blocking" : "timed";
  }

  public String pending(String version, String callId) {
    String result =
        Payloads.json(
            new UserQuestionPort.AnswerBatch(
                null, true, callId, catalog.path("pendingNotice").asText()));
    output(version, result);
    return result;
  }

  public AskUserQuestionPlugin.Input input(String version, String raw) {
    try {
      if (bytes(raw) > QuestionPolicyV1.INPUT_BYTES)
        throw new IllegalArgumentException("Input bytes");
      JsonNode node = STRICT.readTree(raw);
      contract(version).in().validate(node);
      var input = STRICT.treeToValue(node, AskUserQuestionPlugin.Input.class);
      if (node.has("timeout") && !node.get("timeout").isIntegralNumber())
        throw new IllegalArgumentException("Timeout integer");
      var ids = new HashSet<String>();
      for (var q : input.questions()) {
        if (!ids.add(q.id())) throw new IllegalArgumentException("Duplicate question id");
        text(q.question(), QuestionPolicyV1.TEXT_UTF8_BYTES);
        if (q.header() != null) text(q.header(), QuestionPolicyV1.HEADER_UTF8_BYTES);
        if (q.options() != null) {
          var labels = new HashSet<String>();
          for (var option : q.options()) {
            text(option.label(), 256);
            if (!labels.add(option.label())) throw new IllegalArgumentException("Duplicate label");
            if (option.description() != null)
              text(option.description(), QuestionPolicyV1.DESCRIPTION_UTF8_BYTES);
          }
        }
      }
      return input;
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  public List<UserQuestionPort.Answer> canonicalAnswers(
      List<UserQuestionPort.Question> questions, UserQuestionLedger.Command command) {
    try {
      answerSchema.validate(Payloads.read(Payloads.json(command), JsonNode.class));
      var byId = new LinkedHashMap<String, UserQuestionPort.Answer>();
      for (var a : command.answers())
        if (byId.put(a.id(), a) != null) throw new IllegalArgumentException("Duplicate answer");
      if (byId.size() != questions.size()) throw new IllegalArgumentException("Incomplete batch");
      var result = new ArrayList<UserQuestionPort.Answer>();
      for (var q : questions) {
        var a = byId.get(q.id());
        if (a == null) throw new IllegalArgumentException("Unknown answer id");
        var labels =
            q.options() == null
                ? List.<String>of()
                : q.options().stream().map(UserQuestionPort.Option::label).toList();
        if (!labels.containsAll(a.selected())
            || new HashSet<>(a.selected()).size() != a.selected().size())
          throw new IllegalArgumentException("Invalid selection");
        if (!Boolean.TRUE.equals(q.multi_select())
            && (a.selected().size() > 1 || !a.selected().isEmpty() && a.custom() != null))
          throw new IllegalArgumentException("Single selection");
        if (a.custom() != null) text(a.custom(), QuestionPolicyV1.CUSTOM_ANSWER_UTF8_BYTES);
        result.add(
            new UserQuestionPort.Answer(
                q.id(), labels.stream().filter(a.selected()::contains).toList(), a.custom()));
      }
      if (bytes(Payloads.json(result)) > QuestionPolicyV1.ANSWER_BYTES
          || bytes(Payloads.json(new UserQuestionPort.AnswerBatch(result, null, null, null)))
              > QuestionPolicyV1.ANSWER_BYTES) throw new IllegalArgumentException("Answer bytes");
      return List.copyOf(result);
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(400, QuestionErrorCode.QUESTION_ANSWER_INVALID);
    }
  }

  public void output(String version, String value) {
    if (bytes(value) > QuestionPolicyV1.ANSWER_BYTES)
      throw new TaskFailure(409, ToolErrorCode.TOOL_OUTPUT_TOO_LARGE);
    contract(version).out().validate(Payloads.read(value, JsonNode.class));
  }

  private static int bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  private static void text(String value, int maximum) {
    if (value.isBlank() || bytes(value) > maximum)
      throw new IllegalArgumentException("Question text limit");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw new IllegalArgumentException("Invalid Unicode");
      } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
    }
  }
}
