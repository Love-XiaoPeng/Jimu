package io.github.lovexiaopeng.jimu.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionLineageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 真Tomcat/socket、原Controller/filter/handler/pump/cursor；业务ledger替身不代MySQL/M2。 */
class SessionTaskHttpTest {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SessionTaskResponseConfiguration.class, TaskErrors.class})
  static class Web {
    @Bean
    SessionLineageController controller(SessionLineageLedger ledger) {
      return new SessionLineageController(ignored -> new Tasks.Identity("t", "a"), ledger);
    }
  }

  @Test
  void realContainerCompletesProductionJsonAndIncompleteBodyTimesOutWithoutProducer()
      throws Exception {
    var query = new AtomicReference<SessionQueryContext>();
    var releases = new AtomicInteger();
    var producers = new AtomicInteger();
    var value = SessionTaskNonblockingTest.view("真实socket 中文😀", java.time.Instant.EPOCH, 1);
    var ledger =
        (SessionLineageLedger)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionLineageLedger.class},
                (p, m, a) -> {
                  if (!m.getName().equals("submitSessionLineage") || a.length != 5)
                    throw new AssertionError(m);
                  producers.incrementAndGet();
                  var q = new SessionQueryContext((long) a[3], new QueryResources(0, 0, 0));
                  q.restrictLineage();
                  query.set(q);
                  var response = (SessionTaskResponse) a[4];
                  response.bind(
                      new SessionTaskResponse.Budget() {
                        public void retain(Object owner, long bytes) {
                          q.retainScratch(owner, bytes);
                        }

                        public void release(Object owner) {
                          q.releaseScratch(owner);
                          releases.incrementAndGet();
                        }

                        public void wire(int count) {
                          q.operation(count);
                        }

                        public void checkpoint() {
                          q.remainingMillis();
                        }

                        public RuntimeException failure(RuntimeException cause) {
                          return q.recordFailure(cause);
                        }

                        public <T> T primitive(
                            String name, int count, java.util.function.Supplier<T> action) {
                          return q.primitive(name, count, action);
                        }
                      });
                  response.hold(value);
                  return null;
                });
    Path base = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "session-task-http-");
    var tomcat = new Tomcat();
    var spring = new AnnotationConfigWebApplicationContext();
    try {
      tomcat.setBaseDir(base.toString());
      tomcat.getConnector().setPort(0);
      tomcat.getConnector().setProperty("address", "127.0.0.1");
      var context = tomcat.addContext("", base.toString());
      spring.register(Web.class);
      spring.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("ledger", ledger));
      var servlet = Tomcat.addServlet(context, "mvc", new DispatcherServlet(spring));
      servlet.setAsyncSupported(true);
      servlet.setLoadOnStartup(1);
      context.addServletMappingDecoded("/", "mvc");
      var definition = new FilterDef();
      definition.setFilterName("body");
      definition.setFilter(new SessionSearchBodyFilter());
      definition.setAsyncSupported("true");
      context.addFilterDef(definition);
      var mapping = new FilterMap();
      mapping.setFilterName("body");
      mapping.addURLPattern("/*");
      context.addFilterMap(mapping);
      tomcat.start();
      int port = tomcat.getConnector().getLocalPort();
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
        var request =
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/session-trace/tasks"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var result = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        var expected = new MockHttpOutputMessage();
        new JacksonJsonHttpMessageConverter().write(value, MediaType.APPLICATION_JSON, expected);
        assertEquals(202, result.statusCode());
        assertEquals("/api/tasks/caller", result.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
        assertArrayEquals(expected.getBodyAsBytes(), result.body());
        assertEquals(65536, query.get().scratchLive());
        assertEquals(1, releases.get());
      }
      long started = System.nanoTime();
      try (var peer = new Socket("127.0.0.1", port)) {
        peer.setSoTimeout(5000);
        peer.getOutputStream()
            .write(
                ("POST /api/session-trace/tasks HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 20\r\nConnection: close\r\n\r\n{")
                    .getBytes(StandardCharsets.US_ASCII));
        peer.getOutputStream().flush();
        String result = new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(result.startsWith("HTTP/1.1 504"), result);
        assertTrue(result.contains("SESSION_QUERY_TIMEOUT"));
      }
      long elapsed = (System.nanoTime() - started) / 1_000_000;
      assertTrue(elapsed < 4000, "bounded body timer/socket evidence " + elapsed);
      assertEquals(1, producers.get());
      assertEquals(1, releases.get());
      System.out.println(
          "PRODUCTION_HTTP ready202/byte-equivalent/refund1 partial-body504 elapsedMillis="
              + elapsed);
    } finally {
      try {
        tomcat.stop();
      } finally {
        tomcat.destroy();
        spring.close();
      }
      Path expectedRoot = Path.of("target").toAbsolutePath().normalize();
      assertTrue(base.normalize().startsWith(expectedRoot));
      try (var paths = Files.walk(base)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
      assertTrue(Files.notExists(base));
    }
  }
}
