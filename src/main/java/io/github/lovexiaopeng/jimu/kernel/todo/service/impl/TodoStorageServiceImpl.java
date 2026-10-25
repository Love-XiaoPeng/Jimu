package io.github.lovexiaopeng.jimu.kernel.todo.service.impl;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoStorage;
import io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService;

/** 原存储资格；保留可空汇总的拆箱、左结合加法及有符号溢出语义。 */
public final class TodoStorageServiceImpl implements TodoStorageService {
  private static final int METADATA_RESERVE_BYTES = 65536;
  private static final int STORAGE_LIMIT_BYTES = 2097152;

  public void requireCapacity(TodoStorage port, long additional) {
    if (!port.capabilities().contains("todo_write")) return;
    long bytes = port.bytes();
    if (bytes + additional + METADATA_RESERVE_BYTES > STORAGE_LIMIT_BYTES)
      throw new TaskFailure(429, "TODO_STORAGE_LIMIT");
  }
}
