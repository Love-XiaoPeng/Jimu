package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import java.util.Map;

public interface TodoLedger {
  TodoPort.Result replaceOrResult(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String pluginVersion,
      String outputSchemaDigest);

  Map<String, Object> getTodos(Tasks.Identity identity, String taskId);
}
