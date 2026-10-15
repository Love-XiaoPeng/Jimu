package io.github.lovexiaopeng.jimu.kernel.task.api;

public interface ArtifactStore {
  String write(String tenant, String uploadId, byte[] bytes, String hash);

  byte[] read(String tenant, String key, String hash, long size);

  void check();

  void removeUpload(String tenant, String uploadId);
}
