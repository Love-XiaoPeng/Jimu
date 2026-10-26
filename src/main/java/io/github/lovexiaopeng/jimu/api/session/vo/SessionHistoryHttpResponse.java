package io.github.lovexiaopeng.jimu.api.session.vo;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 保留历史服务原始JSON及禁止缓存语义，不重新序列化正文。 */
public final class SessionHistoryHttpResponse {
  private SessionHistoryHttpResponse() {}

  public static ResponseEntity<String> response(String raw) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body(raw);
  }
}
