package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PublicPageProjectionTest {
  @Test
  void htmlGoldenRemovesActiveAndNavigationContentWithoutFetchingSubresources() throws Exception {
    String html =
        "<html><head><title>not evidence</title><style>secret</style></head><body>"
            + "<nav>menu</nav><header>header</header><h1>中文标题</h1><p>安全 &amp; 正文。</p>"
            + "<script>steal()</script><form>password</form><div hidden>hidden</div>"
            + "<iframe src='https://trap.invalid'>frame</iframe><img src='https://trap.invalid/img'>"
            + "<p>&lt;script&gt;literal&lt;/script&gt;</p><footer>footer</footer></body></html>";
    var result =
        PublicPageProjection.project(
            200,
            "https://example.com/",
            "text/html; charset=utf-8",
            html.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: https://example.com/\n\n中文标题 安全 &amp; 正文。 &lt;script&gt;literal&lt;/script&gt;",
        result.content());
    assertFalse(result.truncated());
    new SchemaContract(BuiltinManifestLoader.resource("output.schema.json")).validateObject(result);
  }

  @Test
  void utf8AndSupportedMimeCharsetAreStrict() {
    for (String type :
        new String[] {
          null,
          "application/json",
          "image/png",
          "text/plain; charset=ISO-8859-1",
          "text/html; charset=UTF-16",
          "text/plain; charset=no-such-charset"
        }) {
      assertThrows(
          IOException.class,
          () -> PublicPageProjection.project(200, "https://example.com/", type, new byte[0]));
    }
    assertThrows(
        IOException.class,
        () ->
            PublicPageProjection.project(
                200, "https://example.com/", "text/plain", new byte[] {(byte) 0xc3}));
  }

  @Test
  void modelProjectionAndSerializedCanonicalOutputAreBoundedWithVisibleUtf8SafeTruncation()
      throws Exception {
    for (String text :
        new String[] {"中😀".repeat(90000), "\u0000".repeat(300000), "<>&".repeat(200000)}) {
      var result =
          PublicPageProjection.project(
              200, "https://example.com/", "text/plain", text.getBytes(StandardCharsets.UTF_8));
      assertTrue(result.truncated());
      assertTrue(result.content().endsWith("[TRUNCATED]"));
      assertTrue(result.content().length() <= 200000);
      byte[] serialized = new ObjectMapper().writeValueAsBytes(result);
      assertTrue(serialized.length <= 1048576);
      assertEquals(
          result.content(),
          StandardCharsets.UTF_8
              .newDecoder()
              .decode(java.nio.ByteBuffer.wrap(result.content().getBytes(StandardCharsets.UTF_8)))
              .toString());
      new SchemaContract(BuiltinManifestLoader.resource("output.schema.json"))
          .validateObject(result);
    }
  }

  @Test
  void nonSuccessAndEmptyPagesRemainObservableWithoutInventingEvidence() throws Exception {
    var empty = PublicPageProjection.project(404, "https://example.com/", "text/html", new byte[0]);
    assertEquals(404, empty.httpStatus());
    assertTrue(empty.content().endsWith("\n\n"));
    assertFalse(empty.truncated());
  }
}
