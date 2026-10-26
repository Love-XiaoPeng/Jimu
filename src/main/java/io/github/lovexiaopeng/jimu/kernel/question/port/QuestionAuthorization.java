package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lazy single operations; authorization owns transaction ordering and existence qualification. */
public interface QuestionAuthorization {
  Void transaction(Supplier<Void> work);

  String tenant();

  String taskId();

  String requestId();

  void bucket(String tenant);

  void visible();

  List<Map<String, Object>> rows(String tenant, String task, String request);
}
