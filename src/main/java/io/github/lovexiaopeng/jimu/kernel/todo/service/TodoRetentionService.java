package io.github.lovexiaopeng.jimu.kernel.todo.service;

/** 保留todo正文清理与原事务账目释放。 */
public interface TodoRetentionService {
  void cleanup();
}
