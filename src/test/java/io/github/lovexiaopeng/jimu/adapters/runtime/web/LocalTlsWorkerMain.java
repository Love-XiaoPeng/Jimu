package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.bootstrap.JimuApplication;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebPageReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Test classpath only. Production has no resolver, trust, address or port override. */
public final class LocalTlsWorkerMain {
  private LocalTlsWorkerMain() {}

  public static void main(String[] args) {
    JimuApplication.main(args);
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("local-tls-worker")
  public static class Assembly {
    @Bean
    @Primary
    @Qualifier("publicWebReader")
    WebPageReader localPublicReader(Environment env, OperationThreads operations) throws Exception {
      var keys = KeyStore.getInstance(KeyStore.getDefaultType());
      keys.load(null, null);
      try (var input = Files.newInputStream(Path.of(env.getRequiredProperty("fixture.tls.cert")))) {
        keys.setCertificateEntry(
            "local", CertificateFactory.getInstance("X.509").generateCertificate(input));
      }
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(keys);
      var tls = SSLContext.getInstance("TLS");
      tls.init(new KeyManager[0], trust.getTrustManagers(), null);
      var transport = new PinnedHttpsTransport(tls);
      int port = env.getRequiredProperty("fixture.tls.port", Integer.class);
      Path trace = Path.of(env.getRequiredProperty("fixture.tracker"));
      return new PublicWebReader(
          new PublicWebPolicy(FetchRuntime.domains(env)),
          host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
          (target, pinned, context, active) -> {
            if (pinned.length != 1 || !pinned[0].getHostAddress().equals("93.184.216.34"))
              throw new IllegalStateException("Unexpected policy pins");
            Files.writeString(
                trace,
                "transport "
                    + context.invocationId()
                    + " active="
                    + operations.activeCount()
                    + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            try {
              // Only this test transport maps the already checked logical target onto local TLS.
              URI local =
                  URI.create("https://" + target.getHost() + ":" + port + target.getRawPath());
              var reply =
                  transport.get(
                      local, new InetAddress[] {InetAddress.getLoopbackAddress()}, context, active);
              var body = reply.output();
              return new PinnedHttpsTransport.Reply(
                  reply.status(),
                  reply.location(),
                  body == null
                      ? null
                      : new FetchOutput(
                          body.httpStatus(),
                          target.toString(),
                          body.content()
                              .replace("Source: " + local + "\n\n", "Source: " + target + "\n\n"),
                          body.truncated()));
            } finally {
              Files.writeString(
                  trace,
                  "transport-exit " + context.invocationId() + "\n",
                  StandardOpenOption.APPEND);
            }
          },
          operations);
    }

    @Bean(destroyMethod = "close")
    AutoCloseable trackerReceipt(
        Environment env, OperationThreads operations, WebPageReader reader) {
      return () ->
          Files.writeString(
              Path.of(env.getRequiredProperty("fixture.tracker")),
              "shutdown active=" + operations.activeCount() + "\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
    }
  }
}
