package io.github.lovexiaopeng.jimu.kernel.todo.service;

import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoEligibility;

/** 既有待办任务完整资格检查，不增加事务或受理规则。 */
public interface TodoEligibilityService {
  void access(TodoEligibility records);
}
