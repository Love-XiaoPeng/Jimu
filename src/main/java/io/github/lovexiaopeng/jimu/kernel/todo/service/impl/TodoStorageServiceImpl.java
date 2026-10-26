package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoStorage;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;

/** 原存储资格；保留可空汇总的拆箱、左结合加法及有符号溢出语义。 */
public final class TodoStorageServiceImpl implements TodoStorageService {
  private static final int METADATA_RESERVE_BYTES = TodoPolicyV1.METADATA_BYTES;
  private static final int STORAGE_LIMIT_BYTES = TodoPolicyV1.STORAGE_BYTES;

  public void requireCapacity(TodoStorage port, long additional) {
    if (!port.capabilities().contains(ToolCapability.TODO_WRITE)) return;
    long bytes = port.bytes();
    if (bytes + additional + METADATA_RESERVE_BYTES > STORAGE_LIMIT_BYTES)
      throw new TaskFailure(429, TodoErrorCode.TODO_STORAGE_LIMIT);
  }
}
