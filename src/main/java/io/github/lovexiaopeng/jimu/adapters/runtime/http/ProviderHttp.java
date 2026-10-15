package io.github.lovexiaopeng.jimu.adapters.runtime.http;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ProviderHttp implements AutoCloseable {
  public HttpResponse<byte[]> post(
      URI uri,
      byte[] body,
      Duration timeout,
      String credential,
      Duration connectTimeout,
      int responseBytes)
      throws IOException {
    if (credential != null && !credential.matches("[!-~]{1,4096}"))
      throw new TaskFailure(503, "CREDENTIAL_UNAVAILABLE");
    try (var client =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var request =
          HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json");
      if (credential != null) request.header("Authorization", "Bearer " + credential);
      return BoundedHttp.get(
          client,
          request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
          responseBytes + 1,
          timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskFailure(502, "MODEL_INTERRUPTED");
    }
  }

  @Override
  public void close() {
    // Per-dispatch clients are closed before returning; no credentials/connections are cached.
  }
}
