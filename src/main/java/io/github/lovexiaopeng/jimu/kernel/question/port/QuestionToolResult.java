package io.github.lovexiaopeng.jimu.kernel.question.port;

/** Lazy original reads and individual effects; the service owns qualification and order. */
public interface QuestionToolResult<P> {
  String tenant();

  String invocation();

  P pin();

  String version(P pin);

  void output(String version, String raw);

  String hash(String raw);

  String taskId();

  int completeInvocation(
      String output, String digest, String tenant, String task, String invocation);

  int settleReservation(String tenant, String invocation);

  void event(String fact, String type, String code);
}
