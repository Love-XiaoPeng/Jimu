package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.port.TaskBaseAccess;

/** 当前事务内的任务基础资格编排；scope 后记仍由外层 access 负责。 */
public interface TaskBaseAccessService {
  void require(TaskBaseAccess records);
}
