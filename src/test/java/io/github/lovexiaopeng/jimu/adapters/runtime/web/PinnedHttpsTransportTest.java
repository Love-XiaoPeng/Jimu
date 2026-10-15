package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loopback is passed only to the package-private transport seam, never through public URL policy.
 */
class PinnedHttpsTransportTest {
  @TempDir static Path directory;
  private static SSLContext trusted;

  @BeforeAll
  static void localTlsIdentity() throws Exception {
    Path store = directory.resolve("fixture.p12");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "fixture-only",
                "-keypass",
                "fixture-only",
                "-dname",
                "CN=fixture.example",
                "-ext",
                "SAN=dns:fixture.example",
                "-validity",
                "2",
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("keytool.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS));
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(directory.resolve("keytool.log"));
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
          });
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
    }
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var stream = Files.newInputStream(store)) {
      keys.load(stream, "fixture-only".toCharArray());
    }
    var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keys, "fixture-only".toCharArray());
    var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keys);
    trusted = SSLContext.getInstance("TLS");
    trusted.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
  }

  private static final class Fixture implements AutoCloseable {
    final HttpsServer server;
    final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    final List<com.sun.net.httpserver.Headers> headers = new CopyOnWriteArrayList<>();
    final List<String> sni = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger clientIdentities = new AtomicInteger();

    Fixture() throws IOException {
      server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setHttpsConfigurator(
          new HttpsConfigurator(trusted) {
            @Override
            public void configure(HttpsParameters parameters) {
              var ssl = trusted.getDefaultSSLParameters();
              ssl.setWantClientAuth(true);
              parameters.setSSLParameters(ssl);
            }
          });
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              requests.incrementAndGet();
              headers.add(exchange.getRequestHeaders());
              try {
                ((HttpsExchange) exchange).getSSLSession().getPeerCertificates();
                clientIdentities.incrementAndGet();
              } catch (SSLPeerUnverifiedException e) {
                // Anonymous client: the server requested, but did not require, a certificate.
              }
              for (var name :
                  ((ExtendedSSLSession) ((HttpsExchange) exchange).getSSLSession())
                      .getRequestedServerNames())
                if (name instanceof SNIHostName host) sni.add(host.getAsciiName());
              String path = exchange.getRequestURI().getPath();
              exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
              exchange.getResponseHeaders().set("Set-Cookie", "secret=must-not-replay");
              if (path.equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/target");
                exchange.sendResponseHeaders(302, 0);
                return;
              }
              if (path.equals("/drop")) return;
              if (path.equals("/challenge")) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=fixture");
                exchange.sendResponseHeaders(401, 0);
                return;
              }
              if (path.equals("/header"))
                exchange.getResponseHeaders().set("X-Large", "x".repeat(20000));
              if (path.equals("/mime"))
                exchange.getResponseHeaders().set("Content-Type", "application/json");
              if (path.equals("/charset"))
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
              if (path.equals("/encoding"))
                exchange.getResponseHeaders().set("Content-Encoding", "br");
              byte[] body = "public 中文 body".getBytes(StandardCharsets.UTF_8);
              if (path.equals("/declared")) {
                exchange.sendResponseHeaders(200, PinnedHttpsTransport.MAX_BYTES + 1);
                return;
              }
              if (path.equals("/oversize") || path.equals("/gzip"))
                body = new byte[PinnedHttpsTransport.MAX_BYTES + 1];
              if (path.equals("/exact")) body = new byte[PinnedHttpsTransport.MAX_BYTES];
              if (path.equals("/invalid-utf8")) body = new byte[] {(byte) 0xc3};
              if (path.equals("/gzip-raw")) {
                body = new byte[PinnedHttpsTransport.MAX_BYTES];
                new java.util.Random(40).nextBytes(body);
              }
              if (path.equals("/gzip") || path.equals("/gzip-valid") || path.equals("/gzip-raw")) {
                var compressed = new ByteArrayOutputStream();
                try (var gzip = new GZIPOutputStream(compressed)) {
                  gzip.write(body);
                }
                body = compressed.toByteArray();
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
              }
              exchange.sendResponseHeaders(
                  200, 0); // Chunked: length cannot bypass the streamed bound.
              if (path.equals("/slow")) {
                for (int i = 0; i < 100; i++) {
                  exchange.getResponseBody().write('x');
                  exchange.getResponseBody().flush();
                  try {
                    TimeUnit.MILLISECONDS.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              } else {
                exchange.getResponseBody().write(body);
              }
            } catch (IOException e) {
              // Negative cases deliberately abort TLS sockets and partially written bodies.
            }
          });
      server.start();
    }

    URI target(String path) {
      return URI.create("https://fixture.example:" + server.getAddress().getPort() + path);
    }

    PinnedHttpsTransport.Reply get(String path) throws IOException {
      return new PinnedHttpsTransport(trusted)
          .get(
              target(path),
              new InetAddress[] {InetAddress.getByName("127.0.0.1")},
              PublicWebReaderTest.context(),
              new AtomicReference<>());
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
      try {
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Test
  void actualTlsConnectsOnlyPinnedAddressPreservingHostSniAndIgnoringSystemProxy()
      throws Exception {
    var proxyLookups = new AtomicInteger();
    ProxySelector prior = ProxySelector.getDefault();
    ProxySelector.setDefault(
        new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            proxyLookups.incrementAndGet();
            return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
          }

          @Override
          public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
    try (var fixture = new Fixture()) {
      var reply = fixture.get("/page");
      assertEquals(200, reply.status());
      assertTrue(reply.output().content().endsWith("public 中文 body"));
      assertEquals(
          "fixture.example:" + fixture.server.getAddress().getPort(),
          fixture.headers.getFirst().getFirst("Host"));
      assertEquals(List.of("fixture.example"), fixture.sni);
      assertEquals(0, proxyLookups.get());
      assertNull(fixture.headers.getFirst().getFirst("Authorization"));
      assertNull(fixture.headers.getFirst().getFirst("Cookie"));
      assertNull(fixture.headers.getFirst().getFirst("X-Jimu-Effect-Intent-Id"));
      var fallback =
          new PinnedHttpsTransport(trusted)
              .get(
                  fixture.target("/pinned-fallback"),
                  new InetAddress[] {
                    InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")
                  },
                  PublicWebReaderTest.context(),
                  new AtomicReference<>());
      assertEquals(200, fallback.status());
      assertThrows(
          IOException.class,
          () ->
              new PinnedHttpsTransport(trusted)
                  .get(
                      URI.create(
                          "https://wrong.example:" + fixture.server.getAddress().getPort() + "/"),
                      new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                      PublicWebReaderTest.context(),
                      new AtomicReference<>()));
      assertEquals(2, fixture.requests.get());
    } finally {
      ProxySelector.setDefault(prior);
    }
  }

  @Test
  void anonymousTlsNeverLoadsJvmClientKeyStore() throws Exception {
    var keys =
        List.of(
            "javax.net.ssl.keyStore",
            "javax.net.ssl.keyStorePassword",
            "javax.net.ssl.keyStoreType",
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreType");
    var prior = new java.util.HashMap<String, String>();
    for (String key : keys) prior.put(key, System.getProperty(key));
    try (var fixture = new Fixture()) {
      for (String prefix : List.of("javax.net.ssl.keyStore", "javax.net.ssl.trustStore")) {
        System.setProperty(prefix, directory.resolve("fixture.p12").toString());
        System.setProperty(prefix + "Password", "fixture-only");
        System.setProperty(prefix + "Type", "PKCS12");
      }
      fixture.get("/control-with-client-identity");
      assertEquals(
          1, fixture.clientIdentities.get(), "fixture must actually request client authentication");
      var reply =
          PublicWebReader.defaultTransport()
              .get(
                  fixture.target("/anonymous"),
                  new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                  PublicWebReaderTest.context(),
                  new AtomicReference<>());
      assertEquals(200, reply.status());
      assertEquals(2, fixture.requests.get());
      assertEquals(
          1,
          fixture.clientIdentities.get(),
          "anonymous transport must not send the configured JVM client identity");
    } finally {
      for (String key : keys) {
        if (prior.get(key) == null) System.clearProperty(key);
        else System.setProperty(key, prior.get(key));
      }
    }
  }

  @Test
  void redirectsRetriesAuthenticationAndCookiesAreNeverAutomatic() throws Exception {
    try (var fixture = new Fixture()) {
      var reply = fixture.get("/redirect");
      assertEquals(302, reply.status());
      assertEquals("/target", reply.location());
      assertNull(reply.output());
      assertEquals(1, fixture.requests.get());
      assertEquals(401, fixture.get("/challenge").status());
      assertEquals(2, fixture.requests.get());
      assertThrows(IOException.class, () -> fixture.get("/drop"));
      assertEquals(3, fixture.requests.get());
      fixture.get("/page");
      assertEquals(4, fixture.requests.get());
      for (var headers : fixture.headers) {
        assertNull(headers.getFirst("Authorization"));
        assertNull(headers.getFirst("Cookie"));
      }
    }
  }

  @Test
  void rawAndDecompressedBodiesHeadersAndFormatsHaveIndependentBounds() throws Exception {
    try (var fixture = new Fixture()) {
      for (String path :
          List.of(
              "/oversize", "/gzip", "/header", "/mime", "/charset", "/encoding", "/invalid-utf8"))
        assertThrows(IOException.class, () -> fixture.get(path), path);
      var exact = fixture.get("/exact");
      assertEquals(200, exact.status());
      assertTrue(exact.output().truncated());
      assertTrue(exact.output().content().length() <= 200000);
      assertTrue(fixture.get("/gzip-valid").output().content().endsWith("public 中文 body"));
      assertTrue(
          assertThrows(IOException.class, () -> fixture.get("/gzip-raw"))
              .getMessage()
              .contains("byte limit"));
      assertTrue(
          assertThrows(IOException.class, () -> fixture.get("/declared"))
              .getMessage()
              .contains("transfer limit"));
    }
  }

  @Test
  void slowDripResponseIsAbortedByWholeDeadlineAndCancellation() throws Exception {
    try (var fixture = new Fixture()) {
      for (boolean cancel : new boolean[] {false, true}) {
        var cancelled = new AtomicBoolean();
        var transportThread = new AtomicReference<Thread>();
        long start = System.nanoTime();
        var reader =
            new PublicWebReader(
                new PublicWebPolicy(java.util.Set.of("fixture.example")),
                host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
                (uri, pinned, ctx, request) -> {
                  transportThread.set(Thread.currentThread());
                  // Test only: route to the TLS fixture after reader policy has checked a public
                  // answer.
                  return new PinnedHttpsTransport(trusted)
                      .get(
                          fixture.target("/slow"),
                          new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                          ctx,
                          request);
                });
        var context =
            new ExecutionContext(
                "deadline",
                Instant.now().plusMillis(cancel ? 5000 : 300),
                () -> {
                  if (cancel && System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(300))
                    cancelled.set(true);
                  if (cancelled.get()) throw new CancellationException();
                });
        assertThrows(
            CancellationException.class,
            () -> reader.fetch("https://fixture.example/slow", context));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        if (transportThread.get() != null) {
          transportThread.get().join(2000);
          assertFalse(transportThread.get().isAlive(), "aborted transport must exit");
        }
      }
    }
  }
}
