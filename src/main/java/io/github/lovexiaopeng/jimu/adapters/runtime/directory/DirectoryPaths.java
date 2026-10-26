package io.github.lovexiaopeng.jimu.adapters.runtime.directory;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FileErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

/** Version 1 virtual names. Never converts a model path to a host filesystem path. */
public final class DirectoryPaths {
  private DirectoryPaths() {}

  public static void unicode(String value) {
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (Character.isHighSurrogate(c)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
          throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
      } else if (Character.isLowSurrogate(c))
        throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    }
  }

  private static void size(String value) {
    if (value.length() > 512 || value.getBytes(StandardCharsets.UTF_8).length > 2048)
      throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
  }

  private static boolean whitespace(int c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xfeff;
  }

  public static String resolve(String cwd, String input, boolean directory) {
    unicode(input);
    size(input);
    if (input.isEmpty()) throw new TaskFailure(400, ToolErrorCode.TOOL_INPUT_INVALID);
    if (input.codePoints().allMatch(DirectoryPaths::whitespace)
        || input.contains("//")
        || input.codePoints().anyMatch(c -> c == '\\' || c == ':' || c < 32 || c == 127)
        || !directory && input.endsWith("/")) throw new FileFailure(FileErrorCode.FS_PATH_DENIED);
    var components = new ArrayList<String>();
    if (!input.startsWith("/") && !cwd.equals("/"))
      components.addAll(java.util.List.of(cwd.substring(1).split("/")));
    for (String component : input.split("/", -1)) {
      if (component.isEmpty() || component.equals(".")) continue;
      if (component.equals("..")) {
        if (components.isEmpty()) throw new FileFailure(FileErrorCode.FS_PATH_DENIED);
        components.removeLast();
      } else components.add(component);
    }
    String absolute = "/" + String.join("/", components);
    size(absolute);
    if (!directory && components.isEmpty()) throw new FileFailure(FileErrorCode.FS_PATH_DENIED);
    return directory ? absolute : absolute.substring(1);
  }

  /** Called only with the complete, scope-verified file set under the workspace lock. */
  public static void requireDirectory(String target, Collection<String> files) {
    if (target.equals("/")) return;
    String key = target.substring(1);
    boolean file = files.contains(key);
    boolean ancestor = files.stream().anyMatch(path -> path.startsWith(key + "/"));
    if (file && ancestor) throw new FileFailure(FileErrorCode.FS_NAMESPACE_CONFLICT);
    if (file) throw new FileFailure(FileErrorCode.FS_NOT_DIRECTORY);
    if (!ancestor) throw new FileFailure(FileErrorCode.FS_NOT_FOUND);
  }

  public static void requireFileNamespace(String key, Collection<String> files) {
    if (files.stream().anyMatch(path -> path.startsWith(key + "/") || key.startsWith(path + "/")))
      throw new FileFailure(FileErrorCode.FS_NAMESPACE_CONFLICT);
  }
}
