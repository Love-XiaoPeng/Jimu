package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.plugin.api.FileErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.nio.charset.StandardCharsets;

public record NativeSearchQuery(String tool, String pattern, String path, String include) {
  public NativeSearchQuery {
    if (!tool.equals(ToolCapability.GLOB) && !tool.equals(ToolCapability.GREP))
      throw new IllegalArgumentException("Invalid search tool");
    text(pattern, 8192);
    if (pattern.isEmpty() || tool.equals(ToolCapability.GLOB) && pattern.isBlank())
      throw new IllegalArgumentException("Invalid pattern");
    path = path == null ? "." : path;
    path(path);
    if (include != null) {
      text(include, 4096);
      if (include.isBlank() || include.startsWith("!"))
        throw new IllegalArgumentException("Invalid include");
      int depth = 0;
      for (int i = 0; i < include.length(); i++) {
        char c = include.charAt(i);
        if (c == '{') depth++;
        else if (c == '}') depth--;
        else if (c == ',' && depth == 0) throw new IllegalArgumentException("Invalid include");
      }
    }
  }

  public static void text(String value, int bound) {
    if (value == null
        || value.indexOf('\0') >= 0
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.getBytes(StandardCharsets.UTF_8).length > bound)
      throw new IllegalArgumentException("Invalid search string");
  }

  public static void path(String path) {
    text(path, 2048);
    if (path.equals(".")) return;
    if (path.isBlank()
        || path.length() > 512
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains(":")
        || path.codePoints().anyMatch(c -> c < 32 || c == 127))
      throw new FileFailure(FileErrorCode.FS_PATH_DENIED);
    for (String part : path.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new FileFailure(FileErrorCode.FS_PATH_DENIED);
  }

  public static void cursor(String cursor) {
    if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{22,128}"))
      throw new IllegalArgumentException("Invalid cursor");
  }
}
