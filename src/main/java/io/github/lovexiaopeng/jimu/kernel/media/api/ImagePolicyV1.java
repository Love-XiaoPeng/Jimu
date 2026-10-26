package io.github.lovexiaopeng.jimu.kernel.media.api;

/** jimu-image-v1 冻结准入、归一化和解码政策；格式签名与编码位掩码仍按格式规范原样。 */
public final class ImagePolicyV1 {
  public static final String NORMALIZER_VERSION = "jimu-image-v1";
  public static final String PROJECTION_VERSION = "openai-chat-images-v1";
  public static final int IMAGES = 4;
  public static final int RAW_BYTES = 1048576;
  public static final int TOTAL_RAW_BYTES = 4194304;
  public static final int MAX_DIMENSION = 8192;
  public static final int MAX_PIXELS = 4194304;
  public static final int NORMALIZED_BYTES = 1048576;
  public static final int NORMALIZED_PIXELS = 1048576;
  public static final int NORMALIZED_DIMENSION = 2048;
  public static final int ICC_BYTES = 65536;
  public static final int DECODE_TIMEOUT_SECONDS = 30;
  public static final int DECODE_TIMEOUT_MILLIS = 30000;
  public static final int REQUEST_BYTES = 8388608;
  public static final int CHECKPOINT_BYTES = 12582912;
  public static final long WORKER_HEAP_BYTES = 268435456L;

  public static final int INPUT_PATH_UTF16_UNITS = 512;

  private ImagePolicyV1() {}
}
