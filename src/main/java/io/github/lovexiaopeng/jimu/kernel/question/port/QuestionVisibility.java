package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;
import java.util.Map;

/** 原身份、owner存在行、完整锁行及当前权限的惰性单项操作。 */
public interface QuestionVisibility<T> {
  void identity();

  String tenant();

  String taskId();

  String actor();

  List<Map<String, Object>> owned(String tenant, String taskId, String actor);

  T locked(String tenant, String taskId);

  void access(T task);
}
