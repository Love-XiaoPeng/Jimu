package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoStorage;

public interface TodoStorageService {
  /** 执行用例复用的惰性存储事实；不包含业务决定。 */
  interface Facts extends TodoStorage {}

  default void requireCapacity(Facts facts, long additional) {
    requireCapacity((TodoStorage) facts, additional);
  }

  void requireCapacity(TodoStorage port, long additional);
}
