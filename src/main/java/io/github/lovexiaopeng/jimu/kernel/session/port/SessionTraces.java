package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.function.Supplier;

/** 原生溯源的锁内事实、回放证明及具名结算。 */
public interface SessionTraces {
  <T> T required(Supplier<T> work);

  Task lock(Lease lease);

  String hash(String raw);

  int bytes(String raw);

  record Invocation(String id, String state, String output, String outputDigest) {}

  record Input(long seq, String session) {}

  record Step(String binding, long upper) {}

  record Binding(
      long version,
      String task,
      String step,
      String binding,
      long upper,
      String input,
      long target,
      String policy,
      String content,
      String outcome,
      String failure,
      String outputDigest,
      Long outputBytes,
      Long receipt,
      String attempt,
      Long fence) {}

  record Receipt(String fact, String type, String code) {}

  record Reservation(String category, String state, long actual) {}

  record Budget(long used, long limit) {}

  record Trace(
      String output,
      String scanDigest,
      String relationsDigest,
      String targetDigest,
      int rows,
      int eventBytes,
      int edges) {}

  record Commit(
      String outcome,
      String code,
      String scanDigest,
      String relationsDigest,
      String targetDigest,
      Integer rows,
      Integer eventBytes,
      Integer edges,
      String outputDigest,
      int outputBytes,
      long receipt) {}

  interface Task {
    boolean enabled();

    Invocation originalSlot(String step, Call call, String version, String digest);

    Input input(String raw);

    String historyBinding();

    Step validatedStep(String step);

    Binding lockBinding(String invocation);

    Receipt receipt(long seq);

    Reservation reservation(String invocation);

    void requireAttempt(String attempt, long fence);

    void validateOutput(String output);

    long toolsUsed();

    long maxTools();

    Budget budget();

    Trace trace(Input input, long upper);

    void body(long bytes);

    void consumeTask();

    void consumeTenant();

    void reserve(String invocation);

    void settle(String invocation);

    void finishInvocation(String invocation, String outcome, String output, String digest);

    void event(String fact, String type, String code);

    long receiptSequence(String fact);

    void commitTrace(String invocation, Commit commit);

    void commitFence();

    void deadline();

    void account();
  }
}
