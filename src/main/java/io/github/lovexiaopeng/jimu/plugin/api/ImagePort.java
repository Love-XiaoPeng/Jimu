package io.github.lovexiaopeng.jimu.plugin.api;

/** Read-only task input. The host binds scope, storage, limits and invocation identity. */
public interface ImagePort {
  record Image(
      String imageId,
      String mediaType,
      String sha256,
      long size,
      int width,
      int height,
      int originalWidth,
      int originalHeight,
      String normalizerVersion) {}

  record Result(
      String filePath,
      String inputId,
      String sourceRef,
      String sourceSha256,
      long sourceSize,
      Image image) {}

  Result read(String filePath);
}
