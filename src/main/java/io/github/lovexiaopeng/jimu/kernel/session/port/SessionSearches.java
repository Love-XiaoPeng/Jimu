package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** 原生搜索的锁内事实、回放证明及具名结算。 */
public interface SessionSearches {
  <T> T required(Supplier<T> work);

  Task lock(Lease lease);

  String hash(String raw);

  int bytes(String raw);

  record Invocation(String id, String state, String output, String outputDigest) {}

  record Input(
      String query,
      String session,
      Long from,
      Long to,
      Instant timeFrom,
      Instant timeTo,
      List<String> types) {
    public Input {
      types = List.copyOf(types);
    }
  }

  record Step(String binding, long upper) {}

  record Binding(
      long version,
      String task,
      String step,
      String binding,
      long upper,
      String input,
      String content,
      String normalized,
      String normalizedDigest,
      long normalizedBytes,
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

  record Search(String output, String scanDigest, int rows, int eventBytes) {}

  record Commit(
      String outcome,
      String code,
      String scanDigest,
      Integer rows,
      Integer eventBytes,
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

    String normalized(Input input, long upper);

    Receipt receipt(long seq);

    Reservation reservation(String invocation);

    void requireAttempt(String attempt, long fence);

    void validateOutput(String output);

    long toolsUsed();

    long maxTools();

    Budget budget();

    Search search(Input input, long upper);

    void body(long bytes);

    void consumeTask();

    void consumeTenant();

    void reserve(String invocation);

    void settle(String invocation);

    void finishInvocation(String invocation, String outcome, String output, String digest);

    void event(String fact, String type, String code);

    long receiptSequence(String fact);

    void commitSearch(String invocation, Commit commit);

    void commitFence();

    void deadline();

    void account();
  }
}
