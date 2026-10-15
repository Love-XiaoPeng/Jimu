package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.ConfiguredOriginReader;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8ResponseContractTest {
  private static final int LIMIT = 1048576;

  private static FetchOutput fetch(byte[] bytes) throws Exception {
    HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fixture.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          }
        });
    fixture.start();
    URI origin = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort());
    try (var reader = new ConfiguredOriginReader(origin)) {
      return reader.fetch(
          origin + "/",
          new ExecutionContext("utf8-fixture", Instant.now().plusSeconds(10), () -> {}));
    } finally {
      fixture.stop(0);
    }
  }

  private static void assertContent(FetchOutput output, String expected, boolean truncated) {
    assertFalse(
        output.content().contains("\uFFFD"), "Valid input must not acquire replacement characters");
    int encodedBytes = output.content().getBytes(StandardCharsets.UTF_8).length;
    assertTrue(encodedBytes <= LIMIT, "Re-encoded content exceeded byte limit: " + encodedBytes);
    assertEquals(expected, output.content());
    assertEquals(truncated, output.truncated());
    assertEquals(200, output.httpStatus());
  }

  @Test
  void qaReproductionKeepsWholeCharactersWithinByteLimit() throws Exception {
    String source = "中".repeat(349527);
    assertEquals(1048581, source.getBytes(StandardCharsets.UTF_8).length);
    var output = fetch(source.getBytes(StandardCharsets.UTF_8));
    assertContent(output, "中".repeat(349525), true);
    assertEquals(1048575, output.content().getBytes(StandardCharsets.UTF_8).length);
  }

  @ParameterizedTest(name = "UTF-8 {0}, bytes before limit {1}")
  @CsvSource({
    "é,0", "é,1", "é,2", "中,0", "中,1", "中,2", "中,3", "😀,0", "😀,1", "😀,2", "😀,3", "😀,4"
  })
  void truncationRetainsOnlyCompleteCharacters(String character, int bytesBeforeLimit)
      throws Exception {
    int width = character.getBytes(StandardCharsets.UTF_8).length;
    String prefix = "a".repeat(LIMIT - bytesBeforeLimit);
    String source = prefix + character.repeat(2);
    String expected = prefix + (bytesBeforeLimit == width ? character : "");
    assertContent(fetch(source.getBytes(StandardCharsets.UTF_8)), expected, true);
  }

  @ParameterizedTest(name = "UTF-8 response bytes {0}")
  @ValueSource(ints = {1048575, 1048576, 1048577})
  void responseSizeBoundaryPreservesTextAndTruncatedFlag(int sourceBytes) throws Exception {
    String prefix = "a".repeat(sourceBytes - 3);
    String source = prefix + "中";
    boolean truncated = sourceBytes > LIMIT;
    assertContent(
        fetch(source.getBytes(StandardCharsets.UTF_8)), truncated ? prefix : source, truncated);
  }

  @Test
  void malformedUtf8FailsInsteadOfExpandingReplacementCharacters() {
    for (byte[] source :
        List.of(new byte[] {(byte) 0xc3, 0x28}, new byte[] {(byte) 0xe4, (byte) 0xb8})) {
      var failure = assertThrows(IllegalStateException.class, () -> fetch(source));
      assertTrue(failure.getCause() instanceof CharacterCodingException);
    }
  }
}
