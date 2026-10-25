package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoStorage;

public interface TodoStorageService {
  void requireCapacity(TodoStorage port, long additional);
}
