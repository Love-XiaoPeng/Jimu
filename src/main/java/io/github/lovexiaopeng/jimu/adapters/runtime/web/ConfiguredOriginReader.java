package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.adapters.runtime.http.BoundedHttp;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** P1 fixture provider, restricted to one operator-configured origin, with redirects disabled. */
public final class ConfiguredOriginReader implements WebPageReader, AutoCloseable {
  private static final int OUTPUT_BYTES = 1048576;
  private final URI origin;
  private final HttpClient client;
  private final String credential;
  private final String effectId;

  public ConfiguredOriginReader(URI origin) {
    this(origin, "");
  }

  public ConfiguredOriginReader(URI origin, String credential) {
    this(origin, credential, "");
  }

  public ConfiguredOriginReader(URI origin, String credential, String effectId) {
    if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
      throw new IllegalArgumentException(
          "Provider origin must be an HTTP(S) origin without credentials, query or path");
    }
    this.origin = origin;
    this.credential = credential;
    this.effectId = effectId;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public FetchOutput fetch(String url, ExecutionContext context) {
    URI target = checkedTarget(origin, url);
    return fetchTarget(target, context);
  }

  public static URI checkedTarget(URI origin, String url) {
    URI target = URI.create(url);
    if (!origin.getScheme().equalsIgnoreCase(target.getScheme())
        || !origin.getHost().equalsIgnoreCase(target.getHost())
        || port(origin) != port(target)
        || target.getUserInfo() != null
        || target.getFragment() != null) {
      throw new IllegalArgumentException("URL outside the configured provider origin");
    }
    return target;
  }

  private FetchOutput fetchTarget(URI target, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    Duration remaining = Duration.between(Instant.now(), context.deadline());
    if (remaining.isNegative() || remaining.isZero())
      throw new IllegalArgumentException("Invocation deadline expired");
    Duration timeout =
        remaining.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : remaining;
    try {
      var request = HttpRequest.newBuilder(target).timeout(timeout);
      if (!credential.isEmpty()) request.header("Authorization", "Bearer " + credential);
      if (!effectId.isEmpty()) request.header("X-Jimu-Effect-Intent-Id", effectId);
      var response = BoundedHttp.get(client, request.GET().build(), OUTPUT_BYTES + 1, timeout);
      byte[] bytes = response.body();
      boolean truncated = bytes.length > OUTPUT_BYTES;
      var input = ByteBuffer.wrap(bytes, 0, Math.min(bytes.length, OUTPUT_BYTES));
      var content = CharBuffer.allocate(input.remaining());
      // A truncated response may end partway through a code point; leave those bytes undecoded.
      var decoded = StandardCharsets.UTF_8.newDecoder().decode(input, content, !truncated);
      if (decoded.isError()) decoded.throwException();
      context.cancellation().throwIfCancelled();
      return new FetchOutput(
          response.statusCode(), target.toString(), content.flip().toString(), truncated);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Provider request interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Provider request failed", e);
    }
  }

  private static int port(URI uri) {
    return uri.getPort() != -1
        ? uri.getPort()
        : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
  }

  @Override
  public void close() {
    client.close();
  }
}
