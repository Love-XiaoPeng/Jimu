package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.function.Supplier;

/** 原生读取的锁内事实、协议证明和具名结算，不向业务层暴露持久化行。 */
public interface SessionReads {
  <T> T required(Supplier<T> work);

  Task lock(Lease lease);

  String hash(String raw);

  int bytes(String raw);

  record Invocation(String id, String state, String inputDigest, String output) {}

  record Input(long seq, String sessionId, int before, int after) {}

  record Step(String binding, long upper) {}

  record Binding(
      long version,
      String task,
      String step,
      String binding,
      long upper,
      String input,
      long target,
      long before,
      long after,
      long relation,
      String outcome,
      String failure,
      Long receipt) {}

  record Read(String output, long from, long to, String targetDigest, String sources) {}

  record Budget(long used, long limit) {}

  record Commit(
      String outcome,
      String code,
      Long from,
      Long to,
      String targetDigest,
      String outputDigest,
      int outputBytes,
      long receipt,
      String sources,
      String sourcesDigest,
      Integer sourcesBytes) {}

  interface Task {
    boolean enabled();

    boolean trace();

    Invocation originalSlot(String step, Call call, String version, String digest);

    Input input(String raw);

    String historyBinding();

    Step validatedStep(String step);

    Binding lockBinding(String invocation);

    void verifyReceipt(long seq);

    void validateOutput(String output);

    long toolsUsed();

    long maxTools();

    Budget budget();

    Read read(Input input, long upper);

    void body(long bytes);

    void consumeTask();

    void consumeTenant();

    void reserve(String invocation);

    void settle(String invocation);

    void finishInvocation(String invocation, String outcome, String output, String digest);

    long nextSequence();

    void commitRead(String invocation, Commit commit);

    void event(String fact, String type, String code);

    void appendRead(String step, Invocation invocation);

    void commitFence();

    void account();
  }
}
