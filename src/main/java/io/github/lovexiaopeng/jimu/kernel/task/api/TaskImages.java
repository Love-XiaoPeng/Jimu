package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.kernel.media.api.ImagePolicyV1;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
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
        ImagePolicyV1.NORMALIZER_VERSION,
        ImagePolicyV1.PROJECTION_VERSION,
        ImagePolicyV1.IMAGES,
        ImagePolicyV1.RAW_BYTES,
        ImagePolicyV1.TOTAL_RAW_BYTES,
        ImagePolicyV1.MAX_DIMENSION,
        ImagePolicyV1.MAX_PIXELS,
        ImagePolicyV1.NORMALIZED_BYTES,
        ImagePolicyV1.NORMALIZED_PIXELS,
        ImagePolicyV1.NORMALIZED_DIMENSION,
        ImagePolicyV1.DECODE_TIMEOUT_MILLIS,
        ImagePolicyV1.REQUEST_BYTES,
        ImagePolicyV1.CHECKPOINT_BYTES,
        entries);
  }

  public static String path(String path) {
    if (path == null
        || path.isBlank()
        || path.length() > ImagePolicyV1.INPUT_PATH_UTF16_UNITS
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains(":")
        || path.codePoints().anyMatch(c -> c < 32 || c == 127))
      throw new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
    for (String part : path.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
    if (!path.startsWith("inputs/images/"))
      throw new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
    return path;
  }
}
