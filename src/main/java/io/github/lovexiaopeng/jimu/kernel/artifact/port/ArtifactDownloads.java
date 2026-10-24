package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;

/** 当前事务中的下载事实；授权复核保留原有scope失效登记，不暴露数据库行。 */
public interface ArtifactDownloads {
  record Candidate(Tasks.Artifact artifact, String taskId, String snapshotKey) {}

  void requireIdentity(Tasks.Identity identity);

  Candidate findOwnedRetained(Tasks.Identity identity, String artifactId);

  void requireTaskAccess(Tasks.Identity identity, String taskId);
}
