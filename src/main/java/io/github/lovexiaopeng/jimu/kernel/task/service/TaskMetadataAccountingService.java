package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting;

/** 目录和 todo 的完整元数据计费，沿调用者事件事务执行。 */
public interface TaskMetadataAccountingService {
  void directoryMetadata(TaskMetadataAccounting records, boolean closing);

  void todoMetadata(TaskMetadataAccounting records, boolean closing);
}
