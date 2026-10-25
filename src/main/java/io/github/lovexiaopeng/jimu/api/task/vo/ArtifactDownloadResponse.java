package io.github.lovexiaopeng.jimu.api.task.vo;

import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 仅将已核权读取的产物映射为原下载响应，保留字节引用和表达式求值顺序。 */
public final class ArtifactDownloadResponse {
  private ArtifactDownloadResponse() {}

  public static ResponseEntity<byte[]> from(ArtifactService.Content result) {
    byte[] bytes = result.bytes();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(result.artifact().mediaType()))
        .contentLength(bytes.length)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\""
                + result.artifact().artifactId()
                + (result.artifact().mediaType().equals("text/markdown; charset=utf-8")
                    ? ".md\""
                    : ".txt\""))
        .header("X-Content-Type-Options", "nosniff")
        .eTag(result.artifact().sha256())
        .body(bytes);
  }
}
