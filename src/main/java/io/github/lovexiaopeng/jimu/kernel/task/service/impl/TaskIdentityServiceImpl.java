package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskIdentityRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskIdentityService;
import java.util.List;

/** 身份先于排序；重复项逐次锁定、核资格并按原 long 算术累加。 */
public final class TaskIdentityServiceImpl implements TaskIdentityService {
  public void identity(TaskIdentityRecords records, Identity identity) {
    var result = records.lockActor(identity);
    if (result.isEmpty() || !Boolean.TRUE.equals(result.first().enabled()))
      throw new TaskFailure(403, "ACCESS_REVOKED");
  }

  public long epoch(TaskIdentityRecords records, Identity identity, List<String> capabilities) {
    identity(records, identity);
    long epoch = 0;
    for (String capability : capabilities.stream().sorted().toList()) {
      var installed = records.lockInstallation(identity, capability);
      if (installed.isEmpty() || !Boolean.TRUE.equals(installed.first().enabled()))
        throw new TaskFailure(403, "CAPABILITY_REVOKED");
      epoch += installed.first().epoch().longValue();
    }
    return epoch;
  }
}
