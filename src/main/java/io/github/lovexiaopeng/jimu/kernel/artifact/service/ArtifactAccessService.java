package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;

/** 下载元数据授权用例；返回不可变快照定位，不在账本事务内读取存储。 */
public interface ArtifactAccessService {
  Tasks.Download authorize(Tasks.Identity identity, String artifactId);
}
