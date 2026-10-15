package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * A fresh HTTP/1 TLS connection per hop, with no system DNS, proxy, reuse or automatic followups.
 */
final class PinnedHttpsTransport {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  private final SSLContext tls;

  PinnedHttpsTransport(SSLContext tls) {
    this.tls = tls;
  }

  record Reply(int status, String location, FetchOutput output) {}

  Reply get(
      URI target,
      InetAddress[] addresses,
      ExecutionContext context,
      AtomicReference<HttpUriRequestBase> active)
      throws IOException {
    PublicWebReader.check(context);
    long remaining = Math.max(1, Duration.between(Instant.now(), context.deadline()).toMillis());
    Timeout timeout = Timeout.ofMilliseconds(remaining);
    InetAddress[] pinned = addresses.clone();
    DnsResolver resolver =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!target.getHost().equalsIgnoreCase(host))
              throw new UnknownHostException("Unpinned host");
            return pinned.clone();
          }

          @Override
          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            resolve(host);
            return target.getHost();
          }
        };
    var factory =
        ManagedHttpClientConnectionFactory.builder()
            .http1Config(
                Http1Config.custom()
                    .setMaxHeaderCount(64)
                    .setMaxLineLength(8192)
                    .setMaxEmptyLineCount(2)
                    .build())
            .build();
    var manager =
        new BasicHttpClientConnectionManager(
            new DefaultHttpClientConnectionOperator(
                ignored -> new Socket(Proxy.NO_PROXY),
                DefaultSchemePortResolver.INSTANCE,
                resolver,
                RegistryBuilder.<org.apache.hc.client5.http.ssl.TlsSocketStrategy>create()
                    .register("https", new DefaultClientTlsStrategy(tls))
                    .build()),
            factory);
    manager.setConnectionConfig(
        ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build());
    try (var client =
        HttpClients.custom()
            .setConnectionManager(manager)
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .setConnectionReuseStrategy((request, response, ctx) -> false)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableContentCompression()
            .disableConnectionState()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(timeout)
                    .setConnectionRequestTimeout(timeout)
                    .setAuthenticationEnabled(false)
                    .build())
            .setUserAgent("Jimu-PublicFetch/0.3.0")
            .build()) {
      var request = new HttpGet(target);
      request.setHeader("Accept", "text/html, text/plain");
      request.setHeader("Accept-Encoding", "gzip, identity");
      active.set(request);
      PublicWebReader.check(context);
      return client.execute(
          request,
          response -> {
            try {
              PublicWebReader.check(context);
              int status = response.getCode();
              if (status == 301
                  || status == 302
                  || status == 303
                  || status == 307
                  || status == 308) {
                if (response.countHeaders("Location") != 1)
                  throw new IOException("Redirect requires one Location");
                // Abort instead of draining an untrusted redirect entity on handler cleanup.
                String location = response.getFirstHeader("Location").getValue();
                request.cancel();
                return new Reply(status, location, null);
              }
              if (response.countHeaders("Content-Type") != 1
                  || response.countHeaders("Content-Encoding") > 1)
                throw new IOException("Ambiguous public page headers");
              String type = response.getFirstHeader("Content-Type").getValue();
              String encoding =
                  response.containsHeader("Content-Encoding")
                      ? response.getFirstHeader("Content-Encoding").getValue().trim()
                      : "identity";
              if (!encoding.equalsIgnoreCase("gzip") && !encoding.equalsIgnoreCase("identity"))
                throw new IOException("Unsupported public page content encoding");
              var entity = response.getEntity();
              byte[] body;
              if (entity == null) {
                body = new byte[0];
              } else {
                if (entity.getContentLength() > MAX_BYTES)
                  throw new IOException("Public page transfer limit exceeded");
                try (InputStream raw = new LimitedInput(entity.getContent(), context, request);
                    InputStream decoded =
                        encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(raw) : raw;
                    InputStream bounded = new LimitedInput(decoded, context, request)) {
                  body = bounded.readAllBytes();
                }
              }
              PublicWebReader.check(context);
              return new Reply(
                  status,
                  null,
                  PublicPageProjection.project(status, target.toASCIIString(), type, body));
            } catch (IOException | RuntimeException e) {
              request.cancel();
              throw e;
            }
          });
    } finally {
      // Also close immediately on decoding, header or size failure (no entity draining).
      var request = active.getAndSet(null);
      if (request != null) request.cancel();
    }
  }

  private static final class LimitedInput extends FilterInputStream {
    private final ExecutionContext context;
    private final HttpUriRequestBase request;
    private int count;

    LimitedInput(InputStream input, ExecutionContext context, HttpUriRequestBase request) {
      super(input);
      this.context = context;
      this.request = request;
    }

    @Override
    public void close() throws IOException {
      // Apache entity streams drain on close; abort first, especially on limit/decode failure.
      request.cancel();
      super.close();
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      PublicWebReader.check(context);
      int read = in.read(bytes, offset, Math.min(length, MAX_BYTES - count + 1));
      if (read > 0) count += read;
      if (count > MAX_BYTES) throw new IOException("Public page byte limit exceeded");
      return read;
    }
  }
}
