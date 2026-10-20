package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchInput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublicWebReaderTest {
  private final PublicWebPolicy policy =
      new PublicWebPolicy(Set.of("example.com", "other.example"));

  static ExecutionContext context() {
    return new ExecutionContext("public-fixture", Instant.now().plusSeconds(10), () -> {});
  }

  @Test
  void wallClockRollbackCannotExtendAlreadyAdmittedElapsedBudget() throws Exception {
    // Admission observed a clock one hour ahead; subsequent wall-clock checks observe now.
    // This simulates a rollback without modifying the host clock or shortening the old tests.
    Instant admittedAt = Instant.now().plusSeconds(3600);
    var operations = new OperationThreads();
    var reader =
        new PublicWebReader(
            policy,
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
            (uri, pinned, ctx, active, progress) -> {
              try {
                Thread.sleep(2000);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
              return new PinnedHttpsTransport.Reply(
                  200,
                  null,
                  PublicPageProjection.project(
                      200,
                      uri.toString(),
                      "text/plain",
                      "valid body".getBytes(StandardCharsets.UTF_8)));
            },
            operations,
            java.time.Clock.fixed(admittedAt, java.time.ZoneOffset.UTC));
    long started = System.nanoTime();
    assertThrows(
        CancellationException.class,
        () ->
            reader.fetch(
                "https://example.com/",
                new ExecutionContext("clock-rollback", admittedAt.plusMillis(250), () -> {})));
    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    operations.awaitDrained();
    assertEquals(0, operations.activeCount());
  }

  @Test
  void urlPolicyIsExactHttps443AndNeverAcceptsModelCredentialsOrIpLiterals() {
    assertEquals("example.com", policy.target("https://example.com/page?q=中文").getHost());
    assertEquals(443, policy.target("https://EXAMPLE.com:443/").getPort());
    for (String url :
        List.of(
            "http://example.com",
            "https://example.com:444/",
            "https://user:password@example.com/",
            "https://example.com/#fragment",
            "file:///etc/passwd",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://example.com.evil.org/",
            "https://example.com./",
            "https://sub.example.com/",
            "https://example.com%2fevil.org/",
            "https://example.com\\@evil.org/")) {
      assertThrows(IllegalArgumentException.class, () -> policy.target(url), url);
    }
    for (String domain :
        List.of("*.example.com", "example.com.", "EXAMPLE.com", "127.0.0.1", "localhost"))
      assertThrows(
          IllegalArgumentException.class, () -> new PublicWebPolicy(Set.of(domain)), domain);
    assertThrows(
        IllegalArgumentException.class,
        () -> new PublicWebPolicy(Set.of()).target("https://example.com"));
  }

  @Test
  void everyDnsAnswerMustBePublicIncludingIpv6TransitionAndReservedRanges() throws Exception {
    InetAddress publicV4 = InetAddress.getByName("93.184.216.34");
    InetAddress publicV6 = InetAddress.getByName("2606:4700:4700::1111");
    assertArrayEquals(
        new InetAddress[] {publicV4, publicV6},
        PublicWebPolicy.publicAnswers(new InetAddress[] {publicV4, publicV6}));
    for (String ip :
        List.of(
            "0.1.2.3",
            "10.1.2.3",
            "100.64.0.1",
            "100.127.255.255",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "198.18.0.1",
            "198.19.255.255",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.1.2.3",
            "255.255.255.255",
            "::",
            "::1",
            "fe80::1",
            "fec0::1",
            "fc00::1",
            "ff02::1",
            "64:ff9b::a00:1",
            "64:ff9b:1::1",
            "2001::1",
            "2001:20::1",
            "2001:db8::1",
            "2002:5db8:d822::1",
            "3fff::1")) {
      InetAddress blocked = InetAddress.getByName(ip);
      assertThrows(
          IllegalArgumentException.class,
          () -> PublicWebPolicy.publicAnswers(new InetAddress[] {publicV4, blocked}),
          ip);
    }
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 255;
    mapped[11] = (byte) 255;
    mapped[12] = 93;
    mapped[13] = (byte) 184;
    mapped[14] = (byte) 216;
    mapped[15] = 34;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicWebPolicy.publicAnswers(
                new InetAddress[] {Inet6Address.getByAddress(null, mapped, -1)}));
    assertThrows(
        IllegalArgumentException.class, () -> PublicWebPolicy.publicAnswers(new InetAddress[0]));
  }

  @Test
  void mixedDnsAndRebindingAreDeniedBeforeAnyUncheckedConnection() throws Exception {
    var lookups = new AtomicInteger();
    var requests = new AtomicInteger();
    var reader =
        new PublicWebReader(
            policy,
            host ->
                lookups.incrementAndGet() == 1
                    ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
                    : new InetAddress[] {
                      InetAddress.getByName("93.184.216.34"), InetAddress.getLoopbackAddress()
                    },
            (uri, pinned, ctx, active, progress) -> {
              requests.incrementAndGet();
              assertEquals("93.184.216.34", pinned[0].getHostAddress());
              return new PinnedHttpsTransport.Reply(302, "/next", null);
            });
    assertThrows(
        IllegalArgumentException.class, () -> reader.fetch("https://example.com/start", context()));
    assertEquals(2, lookups.get());
    assertEquals(1, requests.get());
    var mixed =
        new PublicWebReader(
            policy,
            host ->
                new InetAddress[] {
                  InetAddress.getByName("93.184.216.34"), InetAddress.getLoopbackAddress()
                },
            (uri, pinned, ctx, active, progress) -> {
              requests.incrementAndGet();
              throw new IOException("must not connect");
            });
    requests.set(0);
    assertThrows(
        IllegalArgumentException.class, () -> mixed.fetch("https://example.com/", context()));
    assertEquals(0, requests.get());
  }

  @Test
  void redirectsAreManualSameOriginWithExactlyFivePermittedHops() {
    var calls = new AtomicInteger();
    var reader =
        new PublicWebReader(
            policy,
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
            (uri, pinned, ctx, active, progress) ->
                calls.incrementAndGet() <= 5
                    ? new PinnedHttpsTransport.Reply(302, "/" + calls.get(), null)
                    : new PinnedHttpsTransport.Reply(
                        200, null, new FetchOutput(200, uri.toString(), "body", false)));
    assertEquals(
        "https://example.com/5", reader.fetch("https://example.com/", context()).sourceUrl());
    assertEquals(6, calls.get());
    for (String location :
        List.of(
            "/loop",
            "https://other.example/",
            "http://example.com/",
            "https://example.com:444/",
            "https://user@example.com/")) {
      calls.set(0);
      var rejected =
          new PublicWebReader(
              policy,
              host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
              (uri, pinned, ctx, active, progress) -> {
                calls.incrementAndGet();
                return new PinnedHttpsTransport.Reply(302, location, null);
              });
      assertThrows(RuntimeException.class, () -> rejected.fetch("https://example.com/", context()));
      assertEquals(location.equals("/loop") ? 6 : 1, calls.get());
    }
  }

  @Test
  void cancellationAndDeadlineCoverDnsAndNeverPermitALateConnection() throws Exception {
    var operations = new OperationThreads();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var connections = new AtomicInteger();
    var resolverThread = new AtomicReference<Thread>();
    var reader =
        new PublicWebReader(
            policy,
            host -> {
              resolverThread.set(Thread.currentThread());
              entered.countDown();
              // Simulate a platform resolver that cannot be interrupted while it is resolving.
              boolean done = false;
              while (!done) {
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException e) {
                  /* resolve completes later */
                }
              }
              exited.countDown();
              return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            },
            (uri, pinned, ctx, active, progress) -> {
              connections.incrementAndGet();
              throw new IOException("must not connect");
            },
            operations);
    long start = System.nanoTime();
    try {
      assertThrows(
          CancellationException.class,
          () ->
              reader.fetch(
                  "https://example.com/",
                  new ExecutionContext("deadline", Instant.now().plusMillis(200), () -> {})));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
      assertEquals(
          1, operations.activeCount(), "Cancelled Future is not evidence of resolver exit");
    } finally {
      release.countDown();
    }
    assertTrue(exited.await(1, TimeUnit.SECONDS));
    resolverThread.get().join(1000);
    assertFalse(resolverThread.get().isAlive());
    operations.awaitDrained();
    assertEquals(0, operations.activeCount());
    assertEquals(0, connections.get());
    var cancelled = new AtomicBoolean(true);
    assertThrows(
        CancellationException.class,
        () ->
            reader.fetch(
                "https://example.com/",
                new ExecutionContext(
                    "cancelled",
                    Instant.now().plusSeconds(10),
                    () -> {
                      if (cancelled.get()) throw new CancellationException();
                    })));
    assertEquals(0, connections.get());
  }

  @Test
  void publicPluginHasSeparateIdentityWithUnchangedCanonicalSchema() throws Exception {
    var loader = new BuiltinManifestLoader();
    var descriptor = loader.loadPublic();
    assertEquals("0.2.0", loader.load().version());
    assertEquals("0.3.0", descriptor.version());
    assertEquals(loader.load().outputSha256(), descriptor.outputSha256());
    assertEquals(loader.load().outputSchema(), descriptor.outputSchema());
    var reader =
        new PublicWebReader(
            policy,
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
            (uri, pinned, ctx, active, progress) ->
                new PinnedHttpsTransport.Reply(
                    503,
                    null,
                    PublicPageProjection.project(
                        503,
                        uri.toString(),
                        "text/plain; charset=UTF-8",
                        "unavailable".getBytes(StandardCharsets.UTF_8))));
    var output =
        new WebFetchPlugin(reader, descriptor)
            .execute(new FetchInput("https://example.com/"), context())
            .output();
    new SchemaContract(BuiltinManifestLoader.resource("output.schema.json")).validateObject(output);
    assertEquals(503, output.httpStatus());
    assertTrue(output.content().contains("[UNTRUSTED_WEB_CONTENT public-text-v1]"));
    assertFalse(output.truncated());
    assertEquals(4, new ObjectMapper().valueToTree(output).size());
    String manifest;
    try (var stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream(BuiltinManifestLoader.PUBLIC_ROOT + "plugin.yaml")) {
      manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> loader.parse(manifest.getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            loader.parsePublic(
                manifest
                    .replace("operator-public-https-only", "any-origin")
                    .getBytes(StandardCharsets.UTF_8)));
  }
}
