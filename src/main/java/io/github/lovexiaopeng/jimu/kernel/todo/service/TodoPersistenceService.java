package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import java.util.Map;

/** 待办替换、已提交重放及owner快照用例。 */
public interface TodoPersistenceService {
  TodoPort.Result replaceOrResult(
      Lease lease, String stepId, Call call, String version, String digest);

  Map<String, Object> getTodos(Identity identity, String taskId);
}
