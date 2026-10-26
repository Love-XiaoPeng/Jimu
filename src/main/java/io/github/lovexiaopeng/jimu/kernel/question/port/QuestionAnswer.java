package io.github.lovexiaopeng.jimu.kernel.question.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.View;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger.Submission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lazy individual operations. The use case owns all qualification and transaction outcomes. */
public interface QuestionAnswer<Q, A, B, T> {
  record Outcome(Submission value, TaskFailure rejection) {}

  Outcome transaction(Supplier<Outcome> work);

  String tenant();

  String actor();

  String taskId();

  String requestId();

  String key();

  int questionVersion();

  int expectedRevision();

  void bucket(String tenant);

  Map<String, Object> visible();

  List<Map<String, Object>> locked(String tenant, String task, String request);

  List<Map<String, Object>> previous(String tenant, String task, String actor, String key);

  List<Map<String, Object>> reload(String tenant, String request);

  Q inputQuestions(Map<String, Object> question, Map<String, Object> task);

  A canonical(Q questions);

  String digest(String request, A answers);

  Map<String, Object> receipt(Map<String, Object> old);

  Instant now();

  void close(Map<String, Object> task, String code);

  View view(Map<String, Object> task);

  void terminal(Map<String, Object> task, String state, String code);

  boolean enabled();

  void continueQuestion(Map<String, Object> task, Map<String, Object> question);

  String encode(Object value);

  String id();

  Integer sequence(String tenant, String task);

  void storage(Map<String, Object> task, long bytes);

  String hash(String raw);

  T timestamp(Instant now);

  int update(
      String answerId,
      String actor,
      T answeredAt,
      int seq,
      String answer,
      String answerDigest,
      String key,
      String requestDigest,
      int sourceRevision,
      String receipt,
      String delivery,
      String tenant,
      String request,
      long revision);

  B batch(A answers);

  void complete(Map<String, Object> task, Map<String, Object> question, String output);

  void resume(Map<String, Object> task, String request);

  void event(Map<String, Object> task, String fact, String type, String code);
}
