package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoProjections;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoProjectionService;

/** 完整版本、摘要、codec及原串资格；读取与求值顺序沿用原投影合同。 */
public final class TodoProjectionServiceImpl implements TodoProjectionService {
  public <R extends TodoProjections.Projection> R projection(TodoProjections<R> port) {
    var p = port.projection();
    if (p.recordVersion() != 1 || p.todoVersion() != 1)
      throw new TaskFailure(409, "TODO_BINDING_UNSUPPORTED");
    if (!port.hash(p.snapshot()).equals(p.digest())) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var binding = port.decode(p.snapshot());
    if (binding.version() != 1 || binding.todoVersion() != 1)
      throw new TaskFailure(409, "TODO_BINDING_UNSUPPORTED");
    if (!port.expectedBinding().equals(p.snapshot()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    return p;
  }
}
