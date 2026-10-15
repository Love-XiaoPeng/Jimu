package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;

/** Anonymous public HTTPS adapter; not selected by the historical fixture runtime assembly. */
public final class PublicWebReader implements WebPageReader {
  @FunctionalInterface
  interface Lookup {
    InetAddress[] resolve(String host) throws IOException;
  }

  @FunctionalInterface
  interface Transport {
    PinnedHttpsTransport.Reply get(
        URI uri,
        InetAddress[] pinned,
        ExecutionContext context,
        AtomicReference<HttpUriRequestBase> active)
        throws IOException;
  }

  private final PublicWebPolicy policy;
  private final Lookup lookup;
  private final Transport transport;

  public PublicWebReader(Set<String> allowedDomains) {
    this(new PublicWebPolicy(allowedDomains), InetAddress::getAllByName, defaultTransport());
  }

  PublicWebReader(PublicWebPolicy policy, Lookup lookup, Transport transport) {
    this.policy = policy;
    this.lookup = lookup;
    this.transport = transport;
  }

  private static Transport defaultTransport() {
    try {
      return new PinnedHttpsTransport(SSLContext.getDefault())::get;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Default TLS unavailable", e);
    }
  }

  @Override
  public FetchOutput fetch(String url, ExecutionContext original) {
    Instant limit = Instant.now().plusSeconds(30);
    var context =
        new ExecutionContext(
            original.invocationId(),
            original.deadline().isBefore(limit) ? original.deadline() : limit,
            original.cancellation());
    check(context);
    URI first = policy.target(url);
    var active = new AtomicReference<HttpUriRequestBase>();
    var operation = new FutureTask<>(() -> fetchHops(first, context, active));
    Thread.ofVirtual().name("jimu-public-fetch").start(operation);
    try {
      while (true) {
        check(context);
        try {
          return operation.get(
              Math.max(
                  1, Math.min(50, Duration.between(Instant.now(), context.deadline()).toMillis())),
              TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
          // Poll cancellation and the total deadline, including DNS and slow-drip bodies.
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Public fetch interrupted");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Public fetch failed", e.getCause());
    } finally {
      operation.cancel(true);
      var request = active.get();
      if (request != null) request.cancel();
    }
  }

  private FetchOutput fetchHops(
      URI first, ExecutionContext context, AtomicReference<HttpUriRequestBase> active)
      throws IOException {
    URI target = first;
    for (int redirects = 0; ; redirects++) {
      check(context);
      InetAddress[] pinned = PublicWebPolicy.publicAnswers(lookup.resolve(target.getHost()));
      check(context); // A late system DNS completion must never start a connection after
      // cancellation.
      var reply = transport.get(target, pinned, context, active);
      check(context);
      if (reply.location() == null) return reply.output();
      if (redirects == 5) throw new IOException("Public fetch redirect limit exceeded");
      URI next = policy.target(target.resolve(reply.location()).toString());
      if (!first.getHost().equalsIgnoreCase(next.getHost()))
        throw new IOException("Cross-origin redirect denied");
      target = next;
    }
  }

  static void check(ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    if (Thread.currentThread().isInterrupted())
      throw new CancellationException("Public fetch interrupted");
    if (!Instant.now().isBefore(context.deadline()))
      throw new CancellationException("Public fetch deadline expired");
  }
}
