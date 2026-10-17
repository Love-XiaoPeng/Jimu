package io.github.lovexiaopeng.jimu.plugin.api;

/** Host-bound task workspace. Scope, operation identity and CAS are never model arguments. */
public interface FilePort {
  record Read(
      String filePath,
      boolean exists,
      long version,
      String sha256,
      long size,
      int offset,
      int totalLines,
      String content) {}

  record Written(String filePath, long version, String sha256, long size) {}

  record Snapshot(String filePath, long version, String sha256, long size, String content) {}

  Read read(String filePath, int offset, int limit);

  Written write(String filePath, String content);

  /** Pins the exact observed/written version for this present operation. */
  Snapshot snapshot(String filePath);
}
