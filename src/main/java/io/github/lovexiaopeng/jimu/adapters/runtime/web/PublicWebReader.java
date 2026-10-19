package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;

/**
 * Anonymous public HTTPS adapter; shares the worker lifecycle tracker in public runtime assembly.
 */
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
        AtomicReference<HttpUriRequestBase> active,
        Consumer<Phase> progress)
        throws IOException;
  }

  private final PublicWebPolicy policy;
  private final Lookup lookup;
  private final Transport transport;
  private final OperationThreads operations;
  private final Clock admissionClock;

  public PublicWebReader(Set<String> allowedDomains) {
    this(allowedDomains, new OperationThreads());
  }

  public PublicWebReader(Set<String> allowedDomains, OperationThreads operations) {
    this(
        new PublicWebPolicy(allowedDomains),
        InetAddress::getAllByName,
        defaultTransport(),
        operations);
  }

  PublicWebReader(PublicWebPolicy policy, Lookup lookup, Transport transport) {
    this(policy, lookup, transport, new OperationThreads());
  }

  PublicWebReader(
      PublicWebPolicy policy, Lookup lookup, Transport transport, OperationThreads operations) {
    this(policy, lookup, transport, operations, Clock.systemUTC());
  }

  PublicWebReader(
      PublicWebPolicy policy,
      Lookup lookup,
      Transport transport,
      OperationThreads operations,
      Clock admissionClock) {
    this.policy = policy;
    this.lookup = lookup;
    this.transport = transport;
    this.operations = operations;
    this.admissionClock = admissionClock;
  }

  static Transport defaultTransport() {
    try {
      // Do not inherit client identities from the JVM default SSLContext / keyStore properties.
      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(new KeyManager[0], null, null);
      return new PinnedHttpsTransport(tls)::get;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Default TLS unavailable", e);
    }
  }

  @Override
  public FetchOutput fetch(String url, ExecutionContext original) {
    return fetch(url, original, observation -> {});
  }

  public FetchOutput fetch(
      String url, ExecutionContext original, Consumer<WebFetchObservation> observer) {
    var phase = new AtomicReference<>(Phase.ADMISSION);
    Consumer<Phase> progress =
        next -> {
          observer.accept(new WebFetchObservation(next, Failure.NONE));
          phase.set(next);
        };
    try {
      progress.accept(Phase.ADMISSION);
      return fetchObserved(url, original, progress);
    } catch (RuntimeException error) {
      observer.accept(new WebFetchObservation(phase.get(), failure(error)));
      throw error;
    }
  }

  private static Failure failure(Throwable error) {
    // Inspect types only, never parse or persist throwable messages or response content.
    Failure fallback = Failure.INTERNAL;
    for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
      if (error instanceof CancellationException || error instanceof InterruptedException)
        return Failure.CANCELLED;
      if (error instanceof java.net.SocketTimeoutException) return Failure.TIMEOUT;
      if (error instanceof java.net.UnknownHostException) return Failure.DNS;
      if (error instanceof javax.net.ssl.SSLException) return Failure.TLS;
      if (error instanceof IOException) fallback = Failure.IO;
      if (error instanceof IllegalArgumentException) fallback = Failure.POLICY;
    }
    return fallback;
  }

  private FetchOutput fetchObserved(
      String url, ExecutionContext original, Consumer<Phase> progress) {
    Instant admittedAt = admissionClock.instant();
    Instant limit = admittedAt.plusSeconds(30);
    Instant deadline = original.deadline().isBefore(limit) ? original.deadline() : limit;
    long budget = Math.max(0, Duration.between(admittedAt, deadline).toNanos());
    long started = System.nanoTime();
    var closed = new AtomicBoolean();
    var context =
        new ExecutionContext(
            original.invocationId(),
            deadline,
            () -> {
              if (closed.get()) throw new CancellationException("Public fetch closed");
              original.cancellation().throwIfCancelled();
              if (System.nanoTime() - started >= budget)
                throw new CancellationException("Public fetch elapsed deadline expired");
            });
    check(context);
    URI first = policy.target(url);
    var active = new AtomicReference<HttpUriRequestBase>();
    var operation = new FutureTask<>(() -> fetchHops(first, context, active, progress));
    operations.start("jimu-public-fetch", operation);
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
      // Publish before interrupt/abort: a platform DNS resolver may consume the interrupt.
      // The same invocation token is checked after DNS and inside the transport/socket path.
      closed.set(true);
      operation.cancel(true);
      var request = active.get();
      if (request != null) request.cancel();
    }
  }

  private FetchOutput fetchHops(
      URI first,
      ExecutionContext context,
      AtomicReference<HttpUriRequestBase> active,
      Consumer<Phase> progress)
      throws IOException {
    URI target = first;
    for (int redirects = 0; ; redirects++) {
      check(context);
      progress.accept(Phase.DNS);
      InetAddress[] pinned = PublicWebPolicy.publicAnswers(lookup.resolve(target.getHost()));
      check(context); // A late system DNS completion must never start a connection after
      // cancellation.
      progress.accept(Phase.CONNECT_TLS);
      var reply = transport.get(target, pinned, context, active, progress);
      check(context);
      if (reply.location() == null) return reply.output();
      progress.accept(Phase.REDIRECT);
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
