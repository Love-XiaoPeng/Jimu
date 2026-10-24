package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactAccessService;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;

/** 沿现有授权和摘要/长度核验顺序读取不可变快照，存储 IO 位于账本事务之后。 */
public final class ArtifactServiceImpl implements ArtifactService {
  private final ArtifactAccessService access;
  private final ArtifactStore store;

  public ArtifactServiceImpl(ArtifactAccessService access, ArtifactStore store) {
    this.access = access;
    this.store = store;
  }

  @Override
  public Content download(Tasks.Identity identity, String artifactId) {
    var result = access.authorize(identity, artifactId);
    return new Content(
        result.artifact(),
        store.read(
            result.tenant(),
            result.snapshotKey(),
            result.artifact().sha256(),
            result.artifact().size()));
  }
}
