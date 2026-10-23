package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;

/** 产物 owner 授权、快照读取；拒绝发生在存储读取之前。 */
public interface ArtifactService {
  record Content(Tasks.Artifact artifact, byte[] bytes) {}

  Content download(Tasks.Identity identity, String artifactId);
}
