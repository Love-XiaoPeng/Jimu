package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.FetchOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.hc.core5.http.ContentType;
import org.jsoup.Jsoup;

/** public-text-v1: deterministic plain text only; never loads subresources or executes HTML. */
final class PublicPageProjection {
  static final int MAX_CONTENT_CHARS = 200000;
  static final int MAX_OUTPUT_BYTES = 1048576;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TRUNCATED = "\n[TRUNCATED]";

  static FetchOutput project(int status, String url, String contentType, byte[] body)
      throws IOException {
    if (contentType == null) throw new IOException("Missing public page Content-Type");
    ContentType type;
    try {
      type = ContentType.parse(contentType);
    } catch (IllegalArgumentException e) {
      throw new IOException("Unsupported public page Content-Type", e);
    }
    if ((!type.getMimeType().equals("text/html") && !type.getMimeType().equals("text/plain"))
        || (type.getCharset() != null && !type.getCharset().equals(StandardCharsets.UTF_8)))
      throw new IOException("Only UTF-8 HTML/plaintext is supported");
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
    if (type.getMimeType().equals("text/html")) {
      var document = Jsoup.parse(text);
      document
          .select(
              "script,style,noscript,template,form,nav,header,footer,aside,iframe,object,embed,svg,canvas,[hidden],[aria-hidden=true]")
          .remove();
      text = document.body().text();
    }
    // The text projection never emits source HTML tags; the marker applies to the entire payload.
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    String prefix = "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + url + "\n\n";
    int length = Math.min(text.length(), Math.max(0, MAX_CONTENT_CHARS - prefix.length()));
    FetchOutput output;
    do {
      boolean truncated = length < text.length();
      int end = length;
      if (truncated) end = Math.max(0, end - TRUNCATED.length());
      if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      output =
          new FetchOutput(
              status,
              url,
              prefix + text.substring(0, end) + (truncated ? TRUNCATED : ""),
              truncated);
      if (JSON.writeValueAsBytes(output).length <= MAX_OUTPUT_BYTES) return output;
      length /= 2;
    } while (length > 0);
    throw new IOException("Public page output exceeds manifest limit");
  }

  private PublicPageProjection() {}
}
