package io.github.lovexiaopeng.jimu.plugin.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class ImageErrorCode {
  public static final String IMAGE_CODEC_UNAVAILABLE = "IMAGE_CODEC_UNAVAILABLE";
  public static final String IMAGE_COMMIT_REQUIRED = "IMAGE_COMMIT_REQUIRED";
  public static final String IMAGE_DECODE_TIMEOUT = "IMAGE_DECODE_TIMEOUT";
  public static final String IMAGE_DIMENSION_TOO_LARGE = "IMAGE_DIMENSION_TOO_LARGE";
  public static final String IMAGE_FORMAT_UNSUPPORTED = "IMAGE_FORMAT_UNSUPPORTED";
  public static final String IMAGE_STORE_UNAVAILABLE = "IMAGE_STORE_UNAVAILABLE";
  public static final String IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE";
  public static final String IMAGE_TOO_MANY_PIXELS = "IMAGE_TOO_MANY_PIXELS";
  public static final String IMAGE_TYPE_MISMATCH = "IMAGE_TYPE_MISMATCH";
  public static final String IMAGE_UNAVAILABLE = "IMAGE_UNAVAILABLE";
  public static final String IMAGE_WORKER_HEAP_UNBOUNDED = "IMAGE_WORKER_HEAP_UNBOUNDED";
  public static final String INVALID_IMAGE = "INVALID_IMAGE";
  public static final String INVALID_IMAGE_INPUT = "INVALID_IMAGE_INPUT";

  private ImageErrorCode() {}
}
