package io.github.lovexiaopeng.jimu.adapters.runtime.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.http.BoundedHttp;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

public final class HttpResourceProbe implements ResourceProbe, AutoCloseable {
  private final URI target;
  private final boolean worker;
  private final HttpClient client;

  public HttpResourceProbe(URI target, boolean worker) {
    this.target = target;
    this.worker = worker;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public boolean ready() {
    try {
      var response =
          BoundedHttp.get(
              client,
              HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(2)).GET().build(),
              4097,
              Duration.ofSeconds(2));
      if (response.statusCode() != 200) return false;
      if (response.body().length > 4096) return false;
      if (!worker) return true;
      var document = new ObjectMapper().readTree(response.body());
      return document != null
          && "worker".equals(document.path("role").asText())
          && "UP".equals(document.path("status").asText())
          && document.path("plugins").asInt() > 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
