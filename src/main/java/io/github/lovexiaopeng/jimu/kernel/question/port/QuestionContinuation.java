package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;

/**
 * Individual lazy reads and existing effects; continuation qualification belongs to the service.
 */
public interface QuestionContinuation<R, P> {
  String tenant();

  String invocationId();

  String taskId();

  List<R> invocations(String tenant, String invocation, String task);

  P pin();

  String version(P pin);

  String provider(R invocation);

  String pending(String version, String provider);

  void completeOutput(String output);

  String questionId();

  long revision();

  int continuePhase(String tenant, String question, long revision);

  void resume(String requestId);

  void event(String fact, String type, String code);
}
