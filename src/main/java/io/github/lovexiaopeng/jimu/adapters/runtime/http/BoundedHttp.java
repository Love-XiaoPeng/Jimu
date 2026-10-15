package io.github.lovexiaopeng.jimu.adapters.runtime.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds both collected response bytes and the full exchange, including a stalled response body.
 */
public final class BoundedHttp {
  private BoundedHttp() {}

  public static HttpResponse<byte[]> get(
      HttpClient client, HttpRequest request, int prefixBytes, Duration timeout)
      throws IOException, InterruptedException {
    var pending = client.sendAsync(request, info -> new PrefixSubscriber(prefixBytes));
    try {
      return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw new HttpTimeoutException("Response body deadline exceeded");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException io) throw io;
      throw new IOException("HTTP exchange failed", e.getCause());
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private static final class PrefixSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private PrefixSubscriber(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        int count = Math.min(buffer.remaining(), limit - bytes.size());
        byte[] chunk = new byte[count];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
        if (bytes.size() == limit) {
          subscription.cancel();
          body.complete(bytes.toByteArray());
          return;
        }
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
