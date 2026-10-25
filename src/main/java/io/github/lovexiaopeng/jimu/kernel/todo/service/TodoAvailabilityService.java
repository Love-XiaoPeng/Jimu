package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoAvailability;

public interface TodoAvailabilityService {
  boolean available(TodoAvailability records);
}
