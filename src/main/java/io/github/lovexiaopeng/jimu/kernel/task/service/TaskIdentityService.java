package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskIdentityRecords;
import java.util.List;

/** 同一原事务内的身份、逐项安装资格与 epoch 累加。 */
public interface TaskIdentityService {
  void identity(TaskIdentityRecords records, Identity identity);

  long epoch(TaskIdentityRecords records, Identity identity, List<String> capabilities);
}
