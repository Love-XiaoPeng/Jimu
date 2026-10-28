package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.function.Supplier;

/** 每次调用的锁内事实与具名结算；行和JSON留在适配器，不缓存授权。 */
public interface SessionScopeQueries extends SessionScopeNormalization.Proof {
  <T> T required(long deadlineNanos, Supplier<T> work);

  Task lock(Lease lease);

  String hash(String raw);

  int bytes(String raw);

  boolean sameJson(String left, String right);

  record Invocation(
      String id, String state, String inputDigest, String output, String outputDigest) {}

  record Step(String binding, String context) {}

  record Cut(String authority, String context, String snapshot) {}

  record Binding(
      long version,
      String caller,
      String step,
      String operation,
      String binding,
      String input,
      String authority,
      String context,
      String normalized,
      String normalizedDigest,
      long normalizedBytes,
      String content,
      String outcome,
      String failure,
      String outputDigest,
      Long outputBytes,
      Long receipt,
      Long nativeSeq,
      String attempt,
      Long fence,
      String graph) {
    public Binding(
        long version,
        String caller,
        String step,
        String operation,
        String binding,
        String input,
        String authority,
        String context,
        String normalized,
        String normalizedDigest,
        long normalizedBytes,
        String content,
        String outcome,
        String failure,
        String outputDigest,
        Long outputBytes,
        Long receipt,
        Long nativeSeq,
        String attempt,
        Long fence) {
      this(
          version,
          caller,
          step,
          operation,
          binding,
          input,
          authority,
          context,
          normalized,
          normalizedDigest,
          normalizedBytes,
          content,
          outcome,
          failure,
          outputDigest,
          outputBytes,
          receipt,
          nativeSeq,
          attempt,
          fence,
          null);
    }
  }

  record Receipt(String fact, String type, String code) {}

  record Reservation(String category, String state, long actual) {}

  record Budget(long used, long limit) {}

  record LineageProof(int nodes, int edges, long reads, long bytes) {}

  record Result(
      String output,
      String refs,
      String scanDigest,
      int rows,
      int eventBytes,
      LineageProof lineage) {
    public Result(String output, String refs, String scanDigest, int rows, int eventBytes) {
      this(output, refs, scanDigest, rows, eventBytes, null);
    }
  }

  record Commit(
      String outcome,
      String code,
      String scan,
      Integer rows,
      Integer eventBytes,
      String refs,
      String refsDigest,
      Integer refsBytes,
      String outputDigest,
      int outputBytes,
      long receipt,
      Long nativeSeq,
      LineageProof lineage) {
    public Commit(
        String outcome,
        String code,
        String scan,
        Integer rows,
        Integer eventBytes,
        String refs,
        String refsDigest,
        Integer refsBytes,
        String outputDigest,
        int outputBytes,
        long receipt,
        Long nativeSeq) {
      this(
          outcome,
          code,
          scan,
          rows,
          eventBytes,
          refs,
          refsDigest,
          refsBytes,
          outputDigest,
          outputBytes,
          receipt,
          nativeSeq,
          null);
    }
  }

  interface Task {
    boolean scopeTask();

    default boolean lineageTask() {
      return false;
    }

    default String graphDigest() {
      throw new UnsupportedOperationException("v5 graph required");
    }

    default void validateLineageOutput(String output) {
      throw new UnsupportedOperationException("v5 output required");
    }

    default Result trace() {
      throw new UnsupportedOperationException("v5 trace required");
    }

    default void verifyLineageCommit() {
      throw new UnsupportedOperationException("v5 commit required");
    }

    long toolsUsed();

    long maxTools();

    Invocation originalSlot(String step, Call call, String version, String digest);

    Step validatedStep(String step);

    String requireScope();

    Cut cut(String step);

    Binding lockBinding(String invocation);

    String normalized(Call call);

    Receipt receipt(long seq);

    Reservation reservation(String invocation);

    void requireAttempt(String attempt, long fence);

    void validateReadOutput(String output);

    void validateSearchOutput(String output);

    void verifyNative(long seq);

    Budget budget();

    Result read(String normalized);

    Result search(String raw);

    void body(long bytes);

    void validateRefs(String refs);

    int refCount(String refs);

    void consumeTask();

    void consumeTenant();

    void reserve(String invocation);

    void settle(String invocation);

    void finishInvocation(String invocation, String outcome, String output, String digest);

    void event(String fact, String type, String code);

    /** Same transaction; the slot service must account metadata after all event/slot writes. */
    default void eventBeforeFinalAccount(String fact, String type, String code) {
      event(fact, type, code);
    }

    long receiptSequence(String invocation);

    long nextNativeSequence();

    void commitQuery(String invocation, Commit commit);

    void appendRead(String step, Invocation invocation);

    void commitFence();

    void remaining();

    void account();
  }
}
