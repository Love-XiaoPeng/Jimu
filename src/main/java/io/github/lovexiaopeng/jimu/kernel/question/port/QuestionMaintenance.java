package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Each operation is lazy; the service owns scan, transactions and eligibility. */
public interface QuestionMaintenance {
  boolean enabled();

  List<Map<String, Object>> candidates(boolean enabled);

  Void transaction(Supplier<Void> work);

  void bucket(String tenant);

  Map<String, Object> locked(String tenant, String task);

  void access(Map<String, Object> task);

  void close(Map<String, Object> task, String code);

  void terminal(Map<String, Object> task, String state, String code);

  List<Map<String, Object>> due(String tenant, String task);

  void continueQuestion(Map<String, Object> task, Map<String, Object> question);

  void settle(Map<String, Object> task);
}
