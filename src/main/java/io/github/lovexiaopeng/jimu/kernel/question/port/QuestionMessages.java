package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;

/** Single lazy operations; the service owns row order, qualification and map writes. */
public interface QuestionMessages<R, Q, A> {
  boolean questionTask();

  String tenant();

  String taskId();

  List<R> pending(String tenant, String task);

  String answer(R row);

  String hash(String raw);

  String answerDigest(R row);

  String id(R row);

  String provider(R row);

  Q questions(R row);

  A read(String raw);

  String encode(
      int version,
      String kind,
      String tool,
      String requestId,
      String callId,
      Q questions,
      A answers);
}
