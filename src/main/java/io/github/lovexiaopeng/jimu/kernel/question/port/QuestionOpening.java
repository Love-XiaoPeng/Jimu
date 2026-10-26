package io.github.lovexiaopeng.jimu.kernel.question.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 惰性单项操作；资格、分支和绑定求值顺序由用例持有。 */
public interface QuestionOpening<I, P, B, R, T> {
  R transaction(Supplier<R> work);

  Map<String, Object> fenced();

  Map<String, Object> bucket(String tenant);

  String tenant();

  String taskId();

  String actor();

  String step();

  String slot();

  String tool();

  String providerId();

  String rawInput();

  String version();

  String digest();

  boolean enabled();

  I input(String version, String raw);

  List<Map<String, Object>> invocation(String tenant, String task, String step, String slot);

  String hash(String raw);

  P pin(Map<String, Object> task);

  String pinVersion(P pin);

  String pinDigest(P pin);

  long pinEpoch(P pin);

  void output(String version, String raw);

  B readOutput(String raw);

  R result(boolean suspended, B batch);

  Integer ordinal(String tenant, String task);

  void storage(Map<String, Object> task, long bytes);

  String id();

  Integer timeout(I input);

  String mode(String version);

  T timestamp(Instant value);

  int insert(
      String tenant,
      String request,
      String task,
      String actor,
      String invocation,
      int ordinal,
      String binding,
      String mode,
      Integer timeout,
      String raw,
      String digest,
      Integer dueNull,
      Integer dueUnlimited,
      Integer dueSeconds,
      Object deadline,
      T retain);

  int waiting(String request, String tenant, String task);

  int budget(String tenant);

  void reserve(
      String tenant,
      String task,
      String id,
      String dispatch,
      String category,
      long amount,
      String state,
      String quality);

  int waitingInvocation(String tenant, String invocation);

  void pause(Map<String, Object> task, String state, String continuation);

  void event(Map<String, Object> task, String fact, String type, String code);
}
