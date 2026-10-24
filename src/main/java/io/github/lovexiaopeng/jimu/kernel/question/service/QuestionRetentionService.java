package io.github.lovexiaopeng.jimu.kernel.question.service;

/** 同事务清理保留问题正文、释放占用并记录原幂等事件。 */
public interface QuestionRetentionService {
  void cleanup();
}
