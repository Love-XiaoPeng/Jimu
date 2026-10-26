package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeSearchContractTest {
  private NativeResults.Document grep(String raw) throws Exception {
    return NativeResults.collect(
        new NativeSearchQuery("grep", "x", null, null),
        raw.getBytes(StandardCharsets.UTF_8),
        Set.of("a"),
        "snapshot",
        "query",
        "pin");
  }

  private String match(String line, int number) {
    return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a\"},\"lines\":{\"text\":"
        + Payloads.json(line)
        + "},\"line_number\":"
        + number
        + "}}\n";
  }

  @Test
  void byteAndUnicodeBoundariesAreIndependentOfSchemaLength() {
    new NativeSearchQuery("grep", "中".repeat(2730) + "ab", ".", "*.{js,jsx}");
    assertThrows(
        IllegalArgumentException.class,
        () -> new NativeSearchQuery("grep", "中".repeat(2731), null, null));
    for (String input : List.of("", "a\0b", "\uD800"))
      assertThrows(
          IllegalArgumentException.class, () -> new NativeSearchQuery("grep", input, null, null));
    new NativeSearchQuery("grep", " ", null, null);
    for (String input : List.of("!*.js", "a,b", " "))
      assertThrows(
          IllegalArgumentException.class, () -> new NativeSearchQuery("grep", "x", null, input));
    for (String path : List.of("/etc/passwd", "../x", "a/../b", "./a", "a//b", "a\\b", "a\nb"))
      assertThrows(FileFailure.class, () -> new NativeSearchQuery("glob", "*", path, null));
    assertThrows(IllegalArgumentException.class, () -> NativeSearchQuery.cursor("bad"));
  }

  @Test
  void grepPreviewsUseUtf8BoundariesAndStripExactlyOneNewline() throws Exception {
    String raw = match("中".repeat(666) + "🙂\r\n", 7) + match("a\r\n", 8) + match("b\n\n", 9);
    var page = Payloads.read(grep(raw).pages().getFirst(), SearchPort.GrepPage.class);
    assertEquals("中".repeat(666) + "… [truncated]", page.matches().getFirst().line());
    assertTrue(page.matches().getFirst().lineTruncated());
    assertEquals(7, page.matches().getFirst().lineNumber());
    assertEquals("a", page.matches().get(1).line());
    assertEquals("b\n", page.matches().get(2).line());
    assertTrue(page.truncated());
    assertEquals(null, page.nextCursor());
    var exact =
        Payloads.read(
            grep(match("中".repeat(666) + "ab\r\n", 1)).pages().getFirst(),
            SearchPort.GrepPage.class);
    assertEquals(2000, exact.matches().getFirst().line().getBytes(StandardCharsets.UTF_8).length);
    assertTrue(!exact.truncated());
  }

  @Test
  void bytesBranchAndMalformedStreamsCannotBecomeNoMatch() throws Exception {
    var document =
        grep(
            "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a\"},\"lines\":{\"bytes\":\"/wo=\"},\"line_number\":1}}");
    assertTrue(document.pages().getFirst().contains("[binary data]"));
    for (String raw :
        List.of("{", "{}", "{\"type\":\"match\",\"data\":{}}", match("x", 0), match("x", 1) + "{"))
      assertThrows(Exception.class, () -> grep(raw));
  }

  @Test
  void fixedPagesCoverEveryMatchExactlyOnceWithinSerializedByteLimit() throws Exception {
    StringBuilder raw = new StringBuilder();
    for (int i = 1; i <= 601; i++) raw.append(match("x".repeat(2001), i));
    var result = grep(raw.toString());
    var numbers = new HashSet<Long>();
    for (int i = 0; i < result.pages().size(); i++) {
      String json = result.pages().get(i);
      assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 65536);
      var page = Payloads.read(json, SearchPort.GrepPage.class);
      assertEquals(601, page.seen());
      assertTrue(page.returned() <= 250);
      for (var match : page.matches()) assertTrue(numbers.add(match.lineNumber()));
      if (page.nextCursor() != null) assertEquals(i + 1, result.cursors().get(page.nextCursor()));
    }
    assertEquals(601, numbers.size());
    assertTrue(result.pages().size() > 3, "page byte bound precedes match count");
    StringBuilder excessive = new StringBuilder();
    for (int i = 1; i <= 2200; i++) excessive.append(match("x".repeat(2000), i));
    assertEquals(
        "SEARCH_RESULT_LIMIT",
        assertThrows(FileFailure.class, () -> grep(excessive.toString())).code());
  }

  @Test
  void persistedEnvelopeCountsJsonEscapingBeyondCollectedEntries() throws Exception {
    var paths = new ArrayList<String>();
    for (int i = 0; i < 2100; i++) paths.add("\"".repeat(500) + i);
    int collected =
        paths.stream()
            .mapToInt(p -> Payloads.json(p).getBytes(StandardCharsets.UTF_8).length + 1)
            .sum();
    assertTrue(collected < 4194304, "collection fits before pages are escaped into the document");
    var query = new NativeSearchQuery("glob", "*", ".", null);
    var smaller = paths.subList(0, 2000);
    var accepted =
        NativeResults.collect(
            query,
            String.join("\n", smaller).getBytes(StandardCharsets.UTF_8),
            Set.copyOf(smaller),
            "s",
            "q",
            "p");
    assertTrue(Payloads.json(accepted).getBytes(StandardCharsets.UTF_8).length <= 4194304);
    assertEquals(
        "SEARCH_RESULT_LIMIT",
        assertThrows(
                FileFailure.class,
                () ->
                    NativeResults.collect(
                        query,
                        String.join("\n", paths).getBytes(StandardCharsets.UTF_8),
                        Set.copyOf(paths),
                        "s",
                        "q",
                        "p"))
            .code());
  }

  @Test
  void globSamplingIsPersistedAndRemainingOrderHasNoDuplicates() throws Exception {
    var paths = new ArrayList<String>();
    for (int i = 0; i < 110; i++) paths.add("deep/a" + i);
    paths.add("other/z");
    paths.add("root.txt");
    var result =
        NativeResults.collect(
            new NativeSearchQuery("glob", "*", ".", null),
            String.join("\n", paths).getBytes(StandardCharsets.UTF_8),
            Set.copyOf(paths),
            "s",
            "q",
            "p");
    var first = Payloads.read(result.pages().getFirst(), SearchPort.GlobPage.class);
    assertEquals(100, first.returned());
    assertTrue(first.paths().containsAll(List.of("other/z", "root.txt")));
    var all = new ArrayList<String>();
    for (String page : result.pages())
      all.addAll(Payloads.read(page, SearchPort.GlobPage.class).paths());
    assertEquals(paths.size(), all.size());
    assertEquals(new HashSet<>(paths), new HashSet<>(all));
  }
}
