package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoResultSources;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoResultSourceService;

/** Original eight OR terms, exact cardinality and retained-output digest qualification. */
public final class TodoResultSourceServiceImpl implements TodoResultSourceService {
  public void require(TodoResultSources port) {
    if (!port.appliedRevisionPresent()
        || port.appliedRevision() != port.expectedRevision() + 1
        || !"SUCCEEDED".equals(port.state())
        || !"todo_write".equals(port.tool())
        || !port.committedAttemptPresent()
        || !port.committedFencePresent()
        || !port.eventSequencePresent()
        || !port.committedAtPresent()) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (port.attempts().size() != 1) throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    if (!"PURGED".equals(port.contentState())
        && !port.hash(port.outputSnapshot()).equals(port.outputDigest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }
}
