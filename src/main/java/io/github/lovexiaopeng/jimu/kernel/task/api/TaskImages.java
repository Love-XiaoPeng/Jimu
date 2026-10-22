package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.List;

/** Stable task media values, independent of framework, decoder and storage implementation. */
public final class TaskImages {
  private TaskImages() {}

  public record Descriptor(String filePath, String sha256, long size) {}

  public record Input(String filePath, String dataBase64) {}

  public record Entry(String inputId, String filePath, String sha256, long size) {}

  public record Snapshot(
      int version,
      String normalizerVersion,
      String projectionVersion,
      int maxImages,
      long rawBytes,
      long totalRawBytes,
      int maxDimension,
      long maxPixels,
      long normalizedBytes,
      long normalizedPixels,
      int normalizedDimension,
      long decodeTimeoutMs,
      long maxRequestBytes,
      long maxCheckpointBytes,
      List<Entry> entries) {
    public Snapshot {
      entries = List.copyOf(entries);
    }
  }

  public static Snapshot snapshot(List<Entry> entries) {
    return new Snapshot(
        1,
        "jimu-image-v1",
        "openai-chat-images-v1",
        4,
        1048576,
        4194304,
        8192,
        4194304,
        1048576,
        1048576,
        2048,
        30000,
        8388608,
        12582912,
        entries);
  }

  public static String path(String path) {
    if (path == null
        || path.isBlank()
        || path.length() > 512
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains(":")
        || path.codePoints().anyMatch(c -> c < 32 || c == 127))
      throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    for (String part : path.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    if (!path.startsWith("inputs/images/")) throw new TaskFailure(400, "INVALID_IMAGE_INPUT");
    return path;
  }
}
