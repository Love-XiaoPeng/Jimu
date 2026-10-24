package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete native results, with bounded canonical collection and a fixed page permutation. */
public final class NativeResults {
  public record Document(
      String snapshot,
      String query,
      String pin,
      List<String> pages,
      Map<String, Integer> cursors) {}

  private static final JsonFactory JSON =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxStringLength(20000000)
                  .maxDocumentLength(20000000)
                  .maxNestingDepth(32)
                  .build())
          .build();
  private static final SecureRandom RANDOM = new SecureRandom();

  private NativeResults() {}

  public static Document collect(
      NativeSearchQuery query,
      byte[] raw,
      Set<String> files,
      String snapshot,
      String digest,
      String pin)
      throws IOException {
    var paths = new ArrayList<String>();
    var matches = new ArrayList<SearchPort.Match>();
    int bytes = 0;
    if (query.tool().equals("glob")) {
      Set<String> unique = new HashSet<>();
      for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
        if (line.isEmpty()) continue;
        String path = path(line, files);
        if (!unique.add(path)) throw new FileFailure("SEARCH_FAILED");
        bytes += Payloads.json(path).getBytes(StandardCharsets.UTF_8).length + 1;
        if (bytes > 4194304) throw new FileFailure("SEARCH_RESULT_LIMIT");
        paths.add(path);
      }
      paths = sample(paths, query.path());
    } else {
      try (JsonParser parser = JSON.createParser(raw)) {
        while (parser.nextToken() != null) {
          if (parser.currentToken() != JsonToken.START_OBJECT)
            throw new FileFailure("SEARCH_FAILED");
          String type = null, matchPath = null, line = null;
          long lineNumber = 0;
          boolean lineTruncated = false;
          while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            if ("type".equals(key)) type = parser.getValueAsString();
            else if ("data".equals(key) && parser.currentToken() == JsonToken.START_OBJECT) {
              while (parser.nextToken() != JsonToken.END_OBJECT) {
                String dataKey = parser.currentName();
                parser.nextToken();
                if ("line_number".equals(dataKey)
                    && parser.currentToken() == JsonToken.VALUE_NUMBER_INT)
                  lineNumber = parser.getLongValue();
                else if (("path".equals(dataKey) || "lines".equals(dataKey))
                    && parser.currentToken() == JsonToken.START_OBJECT) {
                  String text = null;
                  boolean truncated = false;
                  while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String textKey = parser.currentName();
                    parser.nextToken();
                    if ("text".equals(textKey) && parser.currentToken() == JsonToken.VALUE_STRING) {
                      var preview = new Preview("path".equals(dataKey) ? 2048 : 2000);
                      parser.getText(preview);
                      text = preview.text();
                      truncated = preview.truncated();
                    } else if ("bytes".equals(textKey) && "lines".equals(dataKey)) {
                      text = "[binary data]";
                      parser.skipChildren();
                    } else parser.skipChildren();
                  }
                  if ("path".equals(dataKey)) {
                    if (truncated) throw new FileFailure("SEARCH_FAILED");
                    matchPath = text;
                  } else {
                    line = text;
                    lineTruncated = truncated;
                  }
                } else parser.skipChildren();
              }
            } else parser.skipChildren();
          }
          if ("match".equals(type)) {
            if (matchPath == null || line == null || lineNumber < 1)
              throw new FileFailure("SEARCH_FAILED");
            var match =
                new SearchPort.Match(path(matchPath, files), lineNumber, line, lineTruncated);
            bytes += Payloads.json(match).getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > 4194304) throw new FileFailure("SEARCH_RESULT_LIMIT");
            matches.add(match);
          } else if (!Set.of("begin", "end", "context", "summary")
              .contains(type == null ? "" : type)) throw new FileFailure("SEARCH_FAILED");
        }
      }
    }
    List<String> pages = new ArrayList<>();
    Map<String, Integer> cursors = new LinkedHashMap<>();
    int seen = query.tool().equals("glob") ? paths.size() : matches.size(), position = 0;
    do {
      int max = Math.min(seen, position + (query.tool().equals("glob") ? 100 : 250));
      String page = null, next = null;
      for (int end = max; end >= position; end--) {
        next = end < seen ? cursor() : null;
        if (query.tool().equals("glob"))
          page =
              Payloads.json(
                  new SearchPort.GlobPage(
                      query.path(),
                      paths.subList(position, end),
                      seen,
                      end - position,
                      next != null,
                      next));
        else
          page =
              Payloads.json(
                  new SearchPort.GrepPage(
                      matches.subList(position, end),
                      seen,
                      end - position,
                      next != null
                          || matches.subList(position, end).stream()
                              .anyMatch(SearchPort.Match::lineTruncated),
                      next));
        if (page.getBytes(StandardCharsets.UTF_8).length <= 65536) {
          max = end;
          break;
        }
      }
      if (max == position && position < seen) throw new FileFailure("SEARCH_RESULT_LIMIT");
      pages.add(page);
      if (next != null) cursors.put(next, pages.size());
      position = max;
    } while (position < seen);
    var result = new Document(snapshot, digest, pin, List.copyOf(pages), Map.copyOf(cursors));
    if (Payloads.json(result).getBytes(StandardCharsets.UTF_8).length > 4194304)
      throw new FileFailure("SEARCH_RESULT_LIMIT");
    return result;
  }

  private static String cursor() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String path(String raw, Set<String> files) {
    String value = raw.startsWith("./") ? raw.substring(2) : raw;
    NativeSearchQuery.path(value);
    if (!files.contains(value)) throw new FileFailure("SEARCH_FAILED");
    return value;
  }

  private static ArrayList<String> sample(List<String> paths, String root) {
    var groups = new LinkedHashMap<String, List<Integer>>();
    for (int i = 0; i < paths.size(); i++) {
      String value = paths.get(i);
      if (!root.equals(".") && value.startsWith(root + "/"))
        value = value.substring(root.length() + 1);
      String top = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
      groups.computeIfAbsent(top, key -> new ArrayList<>()).add(i);
    }
    var selected = new HashSet<Integer>();
    for (int offset = 0; selected.size() < Math.min(100, paths.size()); offset++) {
      for (var indices : groups.values()) {
        if (offset < indices.size()) selected.add(indices.get(offset));
        if (selected.size() == Math.min(100, paths.size())) break;
      }
    }
    var ordered = new ArrayList<String>();
    for (var group : groups.values())
      for (int index : group) if (selected.contains(index)) ordered.add(paths.get(index));
    for (int i = 0; i < paths.size(); i++) if (!selected.contains(i)) ordered.add(paths.get(i));
    return ordered;
  }

  private static final class Preview extends Writer {
    private final int bound;
    private final StringBuilder prefix = new StringBuilder();
    private long bytes;
    private char pending;
    private boolean clipped;
    private char last, penultimate;

    Preview(int bound) {
      this.bound = bound;
    }

    @Override
    public void write(char[] chars, int offset, int length) {
      for (int i = offset; i < offset + length; i++) {
        char c = chars[i];
        if (pending != 0) {
          append(new String(new char[] {pending, c}));
          pending = 0;
        } else if (Character.isHighSurrogate(c)) pending = c;
        else append(String.valueOf(c));
      }
    }

    private void append(String text) {
      for (int i = 0; i < text.length(); i++) {
        penultimate = last;
        last = text.charAt(i);
      }
      int size = text.getBytes(StandardCharsets.UTF_8).length;
      bytes += size;
      if (!clipped && bytes <= bound) prefix.append(text);
      else clipped = true;
    }

    String text() {
      String text = prefix.toString();
      long body = bytes - (last == '\n' ? (penultimate == '\r' ? 2 : 1) : 0);
      if (!truncated())
        while (text.getBytes(StandardCharsets.UTF_8).length > body)
          text = text.substring(0, text.length() - 1);
      return truncated() ? text + "… [truncated]" : text;
    }

    boolean truncated() {
      return bytes - (last == '\n' ? (penultimate == '\r' ? 2 : 1) : 0) > bound;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
