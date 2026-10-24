package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import java.util.List;

public interface MediaRetentions {
  record Candidate(String tenant, String taskId) {}

  record Checkpoint(String id, String snapshot, String digest) {}

  record Hold(String id, String category, long amount, Long actual) {}

  interface LockedTask {
    String state();

    String cleanupState();

    boolean writersQuiescent();
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  boolean hasUnknownHold(Candidate candidate);

  void markDeleting(Candidate candidate);

  List<String> deletingObjects(Candidate candidate);

  List<Checkpoint> lockCheckpoints(Candidate candidate);

  void purgeCheckpoint(Candidate candidate, String id, String snapshot, String retainedDigest);

  List<Hold> lockHolds(Candidate candidate);

  void releaseCheckpointBytes(String tenant, long bytes);

  void releaseStorageBytes(String tenant, long bytes);

  void releaseHold(Candidate candidate, String id);

  void purgeInputs(Candidate candidate);

  void markObjectsDeleted(Candidate candidate);

  void markTaskDeleted(Candidate candidate);
}
