package io.github.lovexiaopeng.jimu.kernel.question.port;

/** Lazy single operations; qualification and event ordering belong to the service. */
public interface QuestionResume {
  String tenant();

  String taskId();

  int update(String tenant, String task, String requestId);

  void event(String fact, String type, String code);
}
