package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoAvailability;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoAvailabilityService;

/** Keep the flag outside try, original first row and two separately evaluated descriptors. */
public final class TodoAvailabilityServiceImpl implements TodoAvailabilityService {
  public boolean available(TodoAvailability records) {
    if (!records.enabled()) return false;
    try {
      records.requireTodo(records.selected());
      var installed = records.installed();
      if (installed.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
      var row = installed.get(0);
      return records.descriptor().version().equals(row.version())
          && records.descriptor().outputSha256().equals(row.digest())
          && !records.businessPolicyPresent()
          && !records.scopeRequirementPresent()
          && !records.evaluationRunIdPresent();
    } catch (TaskFailure rejected) {
      return false;
    }
  }
}
