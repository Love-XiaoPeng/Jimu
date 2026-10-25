package io.github.lovexiaopeng.jimu.kernel.question.port;

/** Individual lazy lease reads and writes; the service owns both qualifications. */
public interface QuestionPause {
  String tenant();

  String attemptId();

  long fence();

  String taskId();

  int pauseAttempt(String tenant, String attempt, long fence);

  int pauseTask(String state, String continuation, String tenant, String task, long fence);
}
