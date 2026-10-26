package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;
import java.util.Map;

/** Codec and persistence remain lazy; delivery order belongs to the service. */
public interface QuestionDeliveries<W, M> {
  boolean questionTask();

  W decode(String request);

  Map<String, String> pending();

  List<M> messages(W wire);

  String role(M message);

  String content(M message);

  String tenant();

  int checkpoint(String step, String tenant, String question);

  void clearContinuation();
}
