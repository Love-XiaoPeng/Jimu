package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoEligibility;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoEligibilityService;
import java.util.List;

/** 保留严格列表、pin→安装锁→五项短路比较→投影的原求值顺序。 */
public final class TodoEligibilityServiceImpl implements TodoEligibilityService {
  public void access(TodoEligibility records) {
    if (!records.capabilities().equals(List.of("todo_write")))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var pin = records.pin();
    var installed = records.installation();
    if (!pin.version().equals(records.descriptor().version())
        || !pin.digest().equals(records.descriptor().outputSha256())
        || !pin.version().equals(installed.version())
        || !pin.digest().equals(installed.digest())
        || pin.installationEpoch() != installed.epoch())
      throw new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    records.projection();
  }
}
