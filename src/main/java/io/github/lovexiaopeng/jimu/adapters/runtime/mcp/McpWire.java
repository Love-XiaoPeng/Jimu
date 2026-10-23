package io.github.lovexiaopeng.jimu.adapters.runtime.mcp;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Narrow sessionless Streamable HTTP profile. No DNS, proxies, redirects or implicit retries. */
public final class McpWire implements AutoCloseable {
  public interface Audit {
    int dispatch(String method, String id, String digest, boolean cleanup);

    void responded(int ordinal, String code);

    void check();
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  static {
    JSON.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(1048576).build());
  }

  private final McpLedger.Profile profile;
  private final String credential;
  private final Audit audit;
  private final long end;
  private final HttpClient client;
  private int remaining = 1048576;

  public McpWire(
      McpLedger.Profile profile, String credential, Instant deadline, Duration limit, Audit audit) {
    McpPolicy.profile(profile);
    this.profile = profile;
    this.credential = credential;
    this.audit = audit;
    long nanos =
        Math.min(limit.toNanos(), Math.max(0, Duration.between(Instant.now(), deadline).toNanos()));
    end = System.nanoTime() + nanos;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(
                new ProxySelector() {
                  @Override
                  public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                  }

                  @Override
                  public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                })
            .build();
  }

  public static JsonNode parse(byte[] bytes) {
    try {
      var value = JSON.readTree(utf8(bytes));
      if (value == null) throw invalid();
      return value;
    } catch (IOException error) {
      throw new TaskFailure(502, "MCP_PROTOCOL_INVALID");
    }
  }

  public static String utf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new TaskFailure(502, "MCP_PROTOCOL_INVALID");
    }
  }

  public static byte[] textBytes(String value) {
    try {
      var buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(value));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException error) {
      throw new TaskFailure(502, "MCP_PROTOCOL_INVALID");
    }
  }

  private void check() {
    audit.check();
    if (System.nanoTime() >= end) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
  }

  public void initialize() {
    var value =
        request(
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-06-18",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "jimu", "version", "0.1.0")));
    if (Payloads.json(value).getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
    if (!value.path("protocolVersion").asText().equals(profile.protocolVersion()))
      throw new TaskFailure(502, "MCP_PROFILE_UNSUPPORTED");
    if (!value.path("serverInfo").equals(JSON.valueToTree(profile.serverInfo()))
        || !value.path("capabilities").equals(JSON.valueToTree(profile.serverCapabilities())))
      throw new TaskFailure(409, "MCP_SNAPSHOT_MISMATCH");
    notify("notifications/initialized", Map.of(), false);
  }

  public JsonNode request(String method, Map<String, Object> params) {
    check();
    String id = UUID.randomUUID().toString();
    String body =
        Payloads.json(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    try {
      var response = send(method, id, body, false);
      var values = messages(response);
      JsonNode result = null;
      int notifications = 0;
      for (JsonNode value : values) {
        if (!value.isObject() || !value.path("jsonrpc").asText().equals("2.0")) throw invalid();
        if (value.has("method")) {
          if (!value.path("method").isTextual()) throw invalid();
          if (value.has("id")) {
            send(
                "reverse/error",
                null,
                Payloads.json(
                    Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        value.get("id"),
                        "error",
                        Map.of("code", -32601, "message", "Unsupported client method"))),
                false);
            throw invalid();
          }
          if (++notifications > 16) throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
          if (List.of("notifications/resources/list_changed", "notifications/resources/updated")
              .contains(value.path("method").asText()))
            throw new TaskFailure(409, "MCP_SNAPSHOT_MISMATCH");
          continue;
        }
        if (!value.path("id").isTextual()
            || !value.path("id").asText().equals(id)
            || result != null
            || value.has("result") == value.has("error")) throw invalid();
        if (value.has("error")) {
          var error = value.get("error");
          if (!error.isObject()
              || !error.path("code").isIntegralNumber()
              || !error.path("message").isTextual()) throw invalid();
          throw new TaskFailure(
              error.path("code").asInt() == -32002 ? 404 : 502,
              error.path("code").asInt() == -32002
                  ? "MCP_RESOURCE_NOT_FOUND"
                  : "MCP_UPSTREAM_ERROR");
        }
        result = value.get("result");
        if (!result.isObject()) throw invalid();
      }
      if (result == null) throw invalid();
      return result;
    } catch (TaskFailure error) {
      if (!method.equals("initialize")
          && List.of("CANCEL_REQUESTED", "CANCELLED", "DEADLINE_EXCEEDED").contains(error.code())) {
        try {
          notify(
              "notifications/cancelled", Map.of("requestId", id, "reason", "Task stopped"), true);
        } catch (TaskFailure cleanupFailure) {
          /* Receipt retains cleanup failure; original reason wins. */
        }
      }
      throw error;
    }
  }

  private void notify(String method, Map<String, Object> params, boolean cleanup) {
    var response =
        send(
            method,
            null,
            Payloads.json(Map.of("jsonrpc", "2.0", "method", method, "params", params)),
            cleanup);
    if (response.statusCode() != 202 || response.body().length != 0) throw invalid();
  }

  private HttpResponse<byte[]> send(String method, String id, String body, boolean cleanup) {
    if (!cleanup) check();
    int ordinal = audit.dispatch(method, id, Payloads.hash(body), cleanup);
    var subscriber = new BoundedBody(Math.max(0, remaining));
    CompletableFuture<HttpResponse<byte[]>> future = null;
    try {
      long timeout = cleanup ? TimeUnit.SECONDS.toNanos(1) : end - System.nanoTime();
      if (timeout <= 0) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
      var request =
          HttpRequest.newBuilder(URI.create(profile.endpoint()))
              .timeout(Duration.ofNanos(timeout))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json, text/event-stream")
              .header("Authorization", "Bearer " + credential);
      if (!method.equals("initialize")) request.header("MCP-Protocol-Version", "2025-06-18");
      future =
          client.sendAsync(
              request
                  .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              ignored -> subscriber);
      HttpResponse<byte[]> response;
      long stop = System.nanoTime() + timeout;
      while (true) {
        try {
          response = future.get(100, TimeUnit.MILLISECONDS);
          break;
        } catch (TimeoutException waiting) {
          if (System.nanoTime() >= stop) throw new TaskFailure(409, "DEADLINE_EXCEEDED");
          if (!cleanup) check();
        }
      }
      remaining -= response.body().length;
      if (response.headers().firstValue("Mcp-Session-Id").isPresent())
        throw new TaskFailure(502, "MCP_PROFILE_UNSUPPORTED");
      if (response.statusCode() == 401 || response.statusCode() == 403)
        throw new TaskFailure(503, "MCP_UNAVAILABLE");
      if (response.statusCode() != (id == null ? 202 : 200))
        throw new TaskFailure(502, "MCP_UPSTREAM_ERROR");
      audit.responded(ordinal, null);
      return response;
    } catch (InterruptedException error) {
      java.lang.Thread.currentThread().interrupt();
      audit.responded(ordinal, "CANCELLED");
      throw new TaskFailure(409, "CANCELLED");
    } catch (ExecutionException error) {
      String code = subscriber.exceeded ? "MCP_LIMIT_EXCEEDED" : "MCP_UNAVAILABLE";
      audit.responded(ordinal, code);
      throw new TaskFailure(subscriber.exceeded ? 429 : 503, code);
    } catch (TaskFailure error) {
      audit.responded(ordinal, error.code());
      throw error;
    } finally {
      subscriber.cancel();
      if (future != null && !future.isDone()) future.cancel(true);
    }
  }

  private List<JsonNode> messages(HttpResponse<byte[]> response) {
    String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
    if (type.equalsIgnoreCase("application/json")) return List.of(parse(response.body()));
    if (!type.equalsIgnoreCase("text/event-stream")) throw invalid();
    String text = utf8(response.body());
    if (text.indexOf('\r') >= 0) text = text.replace("\r\n", "\n");
    if (text.indexOf('\r') >= 0) throw invalid();
    var result = new ArrayList<JsonNode>();
    StringBuilder data = new StringBuilder();
    String event = "message";
    for (String line : text.split("\n", -1)) {
      if (line.isEmpty()) {
        if (!data.isEmpty()) {
          if (!event.equals("message")) throw invalid();
          result.add(parse(textBytes(data.substring(0, data.length() - 1))));
          if (result.size() > 17) throw new TaskFailure(429, "MCP_LIMIT_EXCEEDED");
        }
        data.setLength(0);
        event = "message";
      } else if (!line.startsWith(":")) {
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if (field.equals("data")) data.append(value).append('\n');
        if (field.equals("event")) event = value;
      }
    }
    if (!data.isEmpty() || !text.endsWith("\n\n")) throw invalid();
    return result;
  }

  private static TaskFailure invalid() {
    return new TaskFailure(502, "MCP_PROTOCOL_INVALID");
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean cancelled;
    private volatile boolean exceeded;

    BoundedBody(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription value) {
      subscription = value;
      if (cancelled) value.cancel();
      else value.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          exceeded = true;
          subscription.cancel();
          result.completeExceptionally(new IOException("MCP byte limit"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }

    synchronized void cancel() {
      cancelled = true;
      if (subscription != null) subscription.cancel();
    }
  }
}
