package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionTracePolicyV3;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** ADR-0013; seq token spelling is checked before numeric conversion. */
public final class SessionTraceContracts {
  public static final String NAME = ToolCapability.SESSION_EVENT_TRACE;
  public static final String POLICY = "read-receipt-sources-v1";
  private final SessionContracts history = new SessionContracts(3);
  private final SchemaContract input, output;
  private final ToolDescriptor descriptor;
  private final String schema, description;

  public SessionTraceContracts() {
    try {
      var catalog =
          history.json(
              new String(SessionContracts.resource("v3/catalog-v3.json"), StandardCharsets.UTF_8),
              8192);
      var entry = catalog.path("tools").get(2);
      String root = "v3/session_event_trace-0.1.0/";
      byte[] in =
          SessionContracts.checked(root + "input.schema.json", entry.path("inputSha256").asText());
      byte[] out =
          SessionContracts.checked(
              root + "output.schema.json", entry.path("outputSha256").asText());
      SessionContracts.checked(root + "plugin.yaml", entry.path("manifestSha256").asText());
      description =
          new String(
              SessionContracts.checked(
                  root + "description.txt", entry.path("descriptionSha256").asText()),
              StandardCharsets.UTF_8);
      input = new SchemaContract(in);
      output = new SchemaContract(out);
      schema = new String(in, StandardCharsets.UTF_8);
      descriptor =
          new ToolDescriptor(
              "jimu.session.event_trace",
              "0.1.0",
              NAME,
              "session/" + root + "input.schema.json",
              "session/" + root + "output.schema.json",
              Payloads.hash(out),
              "io.github.lovexiaopeng.jimu.plugins.builtin.session.SessionEventTracePlugin");
    } catch (IOException failure) {
      throw new IllegalStateException("Packaged trace contract unavailable", failure);
    }
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

  public SessionEventTracePort.Input input(byte[] raw) {
    try {
      return input(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString());
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  public SessionEventTracePort.Input input(String raw) {
    var value = history.strictJson(raw, SessionTracePolicyV3.INPUT_BYTES);
    try {
      input.validate(value);
      try (var parser = new JsonFactory().createParser(raw)) {
        while (parser.nextToken() != null) {
          if (parser.currentToken() == JsonToken.FIELD_NAME && "seq".equals(parser.currentName())) {
            if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT
                || !parser.getText().matches("0|[1-9][0-9]*"))
              throw new IllegalArgumentException("Seq token");
          }
        }
      }
      return new SessionEventTracePort.Input(
          value.path("seq").longValue(),
          value.has("session_id") ? value.path("session_id").textValue() : null);
    } catch (IOException | IllegalArgumentException failure) {
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  public void output(String raw) {
    if (SessionContracts.bytes(raw) > 32768)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    try {
      output.validate(history.json(raw, SessionTracePolicyV3.OUTPUT_BYTES));
    } catch (IllegalArgumentException | TaskFailure failure) {
      throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
    }
  }
}
