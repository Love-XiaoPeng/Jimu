package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskBaseAccess;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskBaseAccessService;

/** 四项独立资格与 question 专属 else 保持原顺序，MCP 保留重复取值及短路。 */
public final class TaskBaseAccessServiceImpl implements TaskBaseAccessService {
  public void require(TaskBaseAccess records) {
    long currentEpoch =
        records.epoch(new Identity(records.tenant(), records.owner()), records.capabilities());
    if (records.sessionTask()) records.sessionAccess();
    if (records.directoryTask()) records.directoryAccess();
    if (records.todoTask()) records.todoAccess();
    if (records.questionTask()) records.questionAccess();
    else if (currentEpoch != records.installationEpoch())
      throw new TaskFailure(403, "CAPABILITY_REVOKED");
    if (records.capabilities().stream().anyMatch(McpLedger.NAMES::contains)) {
      if (!McpLedger.NAMES.containsAll(records.capabilities()))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      records.mcpBinding();
    } else if (records.hasMcpSnapshot() || records.hasMcpDigest())
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }
}
