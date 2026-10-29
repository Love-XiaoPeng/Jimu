package io.github.lovexiaopeng.jimu.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

/** 当前真实Jackson为独立wire oracle，测试不将业务提交后输出失败称为回滚。 */
class SessionTaskJsonWriterTest {
  @Test
  void frozenWireMatchesJacksonForUnicodeControlsSurrogatesAndNumericExtremes() throws Exception {
    var text = new StringBuilder();
    for (char c = 0; c < 128; c++) text.append(c);
    text.append("字😀\u2028\u2029\uD800x\uDC00\uD800\uD800");
    for (long number : List.of(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE))
      same(view(text.toString(), Instant.EPOCH, number));
    same(view(null, null, 0));
    same(new Tasks.View(null, null, 0, null, null, null, null, null, List.of()));
    for (int base = 128; base < 65536; base += 128) {
      var range = new StringBuilder();
      for (int i = base; i < base + 128; i++) range.append((char) i);
      same(view(range.toString(), Instant.EPOCH, 0));
    }
  }

  @Test
  void dateArithmeticMatchesCurrentJacksonAcrossGregorianCyclesAndAllInstantBounds()
      throws Exception {
    for (Instant time :
        List.of(
            Instant.MIN,
            Instant.MAX,
            Instant.parse("0000-02-29T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59.001Z"),
            Instant.parse("+10000-01-01T00:00:00.000001Z"),
            Instant.parse("1900-03-01T12:34:56.123456789Z"),
            Instant.parse("2000-02-29T23:59:59.123456Z"))) same(view("", time, 0));
    var random = new Random(125126);
    for (int i = 0; i < 128; i++)
      same(
          view(
              "",
              Instant.ofEpochSecond(
                  random.nextLong(-31557014167219200L, 31556889864403199L),
                  random.nextInt(1000000000)),
              i));
  }

  @Test
  void peerFailureRecordsRequestedByteWithoutCompletingItAndDropsReferencesOnce() throws Exception {
    var harness = new Harness(0);
    harness.hold(view("字😀".repeat(200), Instant.EPOCH, 1));
    var accepted = new ByteArrayOutputStream();
    var peer =
        new OutputStream() {
          public void write(int value) throws IOException {
            if (accepted.size() == 17) throw new IOException("peer marker");
            accepted.write(value);
          }
        };
    assertEquals(
        "peer marker", assertThrows(IOException.class, () -> harness.write(peer)).getMessage());
    assertEquals(17, accepted.size());
    assertEquals(18, harness.query.requestedWork().get("response.wire"));
    assertEquals(17, harness.query.completedWork().get("response.wire"));
    harness.released();
  }

  @Test
  void scratchRefusalHappensBeforeObtainingSinkAndFailedBudgetCannotEmit() throws Exception {
    var harness = new Harness(0);
    harness.hold(view("", Instant.EPOCH, 0));
    Object pressure = new Object();
    harness.query.retainScratch(
        pressure,
        1048576 - harness.query.scratchLive() - SessionTaskJsonWriter.WORKSPACE_BYTES + 1);
    boolean[] opened = {false};
    var output =
        new HttpOutputMessage() {
          public HttpHeaders getHeaders() {
            return new HttpHeaders();
          }

          public OutputStream getBody() {
            opened[0] = true;
            return OutputStream.nullOutputStream();
          }
        };
    assertThrows(
        TaskFailure.class,
        () ->
            new SessionTaskResponseConfiguration.Converter()
                .write(harness.response, MediaType.APPLICATION_JSON, output));
    assertFalse(opened[0]);
    harness.query.releaseScratch(pressure);
    harness.released();
    var exhausted = new Harness(65536);
    exhausted.hold(view("", Instant.EPOCH, 0));
    var bytes = new ByteArrayOutputStream();
    assertThrows(TaskFailure.class, () -> exhausted.write(bytes));
    assertEquals(0, bytes.size());
    exhausted.released();
  }

  private static Tasks.View view(String text, Instant time, long number) {
    return new Tasks.View(
        "caller",
        "QUEUED",
        number,
        time,
        time,
        time,
        text,
        text,
        List.of(
            new Tasks.Artifact(text, text, number, text, time),
            new Tasks.Artifact(null, null, 0, null, null)));
  }

  private static void same(Tasks.View value) throws Exception {
    var original = new MockHttpOutputMessage();
    new JacksonJsonHttpMessageConverter().write(value, MediaType.APPLICATION_JSON, original);
    var harness = new Harness(0);
    harness.hold(value);
    var actual = new ByteArrayOutputStream();
    harness.write(actual);
    assertArrayEquals(original.getBodyAsBytes(), actual.toByteArray());
    assertTrue(harness.query.resources().operations() > actual.size());
    assertEquals((long) actual.size(), harness.query.completedWork().get("response.wire"));
    harness.released();
  }

  private static final class Harness {
    final SessionQueryContext query;
    final SessionTaskResponse response = new SessionTaskResponse();
    int releases;

    Harness(long prior) {
      query =
          new SessionQueryContext(
              System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, prior));
      query.restrictLineage();
      response.bind(
          new SessionTaskResponse.Budget() {
            public void retain(Object owner, long bytes) {
              query.retainScratch(owner, bytes);
            }

            public void release(Object owner) {
              releases++;
              query.releaseScratch(owner);
            }

            public void wire(int count) {
              query.operation(count);
            }

            public <T> T primitive(String name, int count, java.util.function.Supplier<T> action) {
              return query.primitive(name, count, action);
            }
          });
    }

    void hold(Tasks.View view) {
      response.retain(160000);
      response.hold(view);
      response.produced();
    }

    void write(OutputStream stream) throws IOException {
      new SessionTaskResponseConfiguration.Converter()
          .write(
              response,
              MediaType.APPLICATION_JSON,
              new HttpOutputMessage() {
                private final HttpHeaders headers = new HttpHeaders();

                public HttpHeaders getHeaders() {
                  return headers;
                }

                public OutputStream getBody() {
                  return stream;
                }
              });
    }

    void released() throws Exception {
      response.close();
      response.produced();
      assertEquals(1, releases);
      assertEquals(65536, query.scratchLive());
      for (String name : List.of("view", "budget")) {
        var field = SessionTaskResponse.class.getDeclaredField(name);
        field.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(field.get(response));
      }
    }
  }
}
