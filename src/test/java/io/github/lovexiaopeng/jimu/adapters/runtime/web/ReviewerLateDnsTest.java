package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReviewerLateDnsTest {
  @Test
  void callerInterruptionMustPreventLateDnsConnection() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var resolver = new AtomicReference<Thread>();
    var outcome = new AtomicReference<Throwable>();
    var connections = new AtomicInteger();
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host -> {
              resolver.set(Thread.currentThread());
              entered.countDown();
              boolean done = false;
              while (!done) {
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                  /* platform resolver consumes interrupt */
                }
              }
              return new InetAddress[] {
                InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})
              };
            },
            (uri, addresses, ctx, active) -> {
              connections.incrementAndGet();
              return new PinnedHttpsTransport.Reply(
                  200, null, new FetchOutput(200, uri.toString(), "late", false));
            });
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    reader.fetch(
                        "https://example.com/",
                        new ExecutionContext("review", Instant.now().plusSeconds(20), () -> {}));
                  } catch (Throwable failure) {
                    outcome.set(failure);
                  }
                });
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      caller.interrupt();
      caller.join(3000);
      assertFalse(caller.isAlive());
      assertInstanceOf(CancellationException.class, outcome.get());
    } finally {
      release.countDown();
      caller.join(3000);
      if (resolver.get() != null) resolver.get().join(3000);
      System.out.println(
          "REVIEW callerExited="
              + !caller.isAlive()
              + " resolverExited="
              + (resolver.get() != null && !resolver.get().isAlive())
              + " lateTransportCalls="
              + connections.get()
              + " realNetworkCalls=0");
    }
    assertFalse(resolver.get().isAlive(), "Resolver worker must converge after fixture release");
    assertEquals(
        0, connections.get(), "caller cancellation must prevent late DNS from starting transport");
  }
}
