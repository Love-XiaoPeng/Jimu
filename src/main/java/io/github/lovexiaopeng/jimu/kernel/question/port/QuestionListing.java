package io.github.lovexiaopeng.jimu.kernel.question.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lazy single operations; the use case owns qualification, fields, and short circuiting. */
public interface QuestionListing<Q, A> {
  Map<String, Object> transaction(Supplier<Map<String, Object>> work);

  String tenant();

  String taskId();

  void bucket(String tenant);

  Map<String, Object> visible();

  Instant now();

  List<Map<String, Object>> rows(String tenant, String taskId);

  Q questions(Map<String, Object> question, Map<String, Object> task);

  boolean enabled();

  View view(Map<String, Object> task);

  A readAnswer(String raw);

  String encode(Map<String, Object> result);
}
