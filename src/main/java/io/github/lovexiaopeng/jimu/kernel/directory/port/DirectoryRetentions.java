package io.github.lovexiaopeng.jimu.kernel.directory.port;

import java.time.Instant;
import java.util.List;

/** 工作区保留事实；数据库与文件系统分别通过窄端口访问，不把Map或存储实现传入内核。 */
public interface DirectoryRetentions {
  record Candidate(String tenant, String taskId) {}

  record Projection(
      String state, Instant retainUntil, String generation, String receipt, String cwd) {}

  record Body(String field, String value) {}

  record Content(String id, List<Body> bodies) {}

  record RetainedBody(String key, String sha256, long bytes) {}

  interface Workspace {
    boolean available();

    String plan(Candidate candidate);

    void purge(Candidate candidate, String receipt);
  }

  interface LockedTask {
    Projection projection();

    String state();

    boolean writersQuiescent();

    boolean unsafeReservations();

    boolean activeInvocations();

    boolean dispatchedModels();

    boolean directoryCapability();

    long storageBytes();

    List<Body> bodies();

    List<Content> steps();

    List<Content> invocations();

    List<Content> paths();

    List<Content> dispatches();

    void purgeStep(String id, String tombstone);

    void purgeInvocation(String id, String tombstone);

    void purgePath(String id);

    void purgeDispatch(String id, String tombstone);

    void purgeTask(String tombstone);

    void markPurging(String retained);

    void storeReceipt(String receipt);

    void markPurged();

    void event();

    int releaseHold();

    void releaseBudget();
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  Instant now();

  String metadata(List<RetainedBody> bodies);
}
