package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;

public interface MediaRetentionService {
  void cleanup(ArtifactStore store);
}
