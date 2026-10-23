package io.github.lovexiaopeng.jimu.kernel.worker.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;

/** Locked handles are transaction-local; implementations cannot open a transaction. */
public interface WorkerRecords {
  record Candidate(String tenant, String taskId, String evaluationRun) {}

  interface Claim {
    boolean runUnchanged();

    String state();

    boolean requiresStoppedWriters();

    boolean writersQuiescent();

    boolean runReserved();

    boolean runningLimitReached();

    boolean pendingEffect();

    String modelRejection();

    String runRejection();

    void requireScope();

    void markRunningInvocationsUnknown();

    void terminal(String state, String code);

    void expireCurrentAttempt();

    boolean withinDeadline();

    void checkAdmission(boolean resume);

    boolean cancellationRequested();

    long fence();

    void moveQueueToRunning();

    void resumeRunning();

    void insertAttempt(String worker, String attempt, long fence);

    void assignLease(String worker, String attempt, long fence);

    void startedEvent(long fence);

    Lease lease(String worker, String attempt, long fence);
  }

  void maintainQuestions();

  List<String> tenants();

  java.util.Optional<Candidate> candidate(String tenant);

  java.util.Optional<Claim> lockCandidate(Candidate candidate);

  record TaskKey(String tenant, String taskId) {}

  interface Task {
    boolean nativeAttemptActive(Lease lease);

    void markAttemptQuiescent(Lease lease);

    void markWorkerAttemptsQuiescent(String worker);

    boolean ownedBy(String worker);

    void expireLease();

    void reconcileStoppedWriters();
  }

  boolean nativeWorkerActive(String worker);

  List<TaskKey> workerTasks(String worker);

  Task lockTask(TaskKey key);

  void markWorkerStopped(String worker);
}
