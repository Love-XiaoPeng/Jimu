package io.github.lovexiaopeng.jimu.adapters.runtime.http;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ProviderHttp implements AutoCloseable {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public HttpResponse<byte[]> post(URI uri, byte[] body, Duration timeout, String credential)
      throws IOException {
    try {
      return BoundedHttp.get(
          client,
          HttpRequest.newBuilder(uri)
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + credential)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build(),
          1048577,
          timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskFailure(502, "MODEL_INTERRUPTED");
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
