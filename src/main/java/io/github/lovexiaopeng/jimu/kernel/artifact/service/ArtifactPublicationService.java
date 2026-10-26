package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;

/** 普通工具产物的暂存、核验登记和幂等发布；存储 IO 由调用方在事务外完成。 */
public interface ArtifactPublicationService {
  Upload stage(Lease lease, String hash, long size);

  void verified(Lease lease, String artifactId, String key);

  void publish(Lease lease, String artifactId);
}
