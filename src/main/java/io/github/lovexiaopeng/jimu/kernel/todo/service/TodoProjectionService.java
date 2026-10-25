package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoProjections;

public interface TodoProjectionService {
  <R extends TodoProjections.Projection> R projection(TodoProjections<R> port);
}
