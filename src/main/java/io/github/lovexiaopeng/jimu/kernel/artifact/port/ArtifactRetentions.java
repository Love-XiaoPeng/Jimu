package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import java.time.Instant;
import java.util.List;

/** 保留清理所需的类型化事实；锁句柄只在调用者的同一UoW内使用。 */
public interface ArtifactRetentions {
  record Candidate(String tenant, String artifactId, String taskId, String uploadId) {}

  record Hold(String state, long amount) {}

  interface LockedTask {
    String state();

    boolean writersQuiescent();
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  Instant lockArtifactExpiry(Candidate candidate);

  void mark(Candidate candidate, String state);

  Hold hold(Candidate candidate);

  void releaseStorage(String tenant, long amount);

  void releaseHold(Candidate candidate);
}
