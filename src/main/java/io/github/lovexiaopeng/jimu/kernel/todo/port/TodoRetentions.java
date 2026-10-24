package io.github.lovexiaopeng.jimu.kernel.todo.port;

import java.time.Instant;
import java.util.List;

/** 保留清理专用事实与具名写入；独立todo业务仍由其原路径负责。 */
public interface TodoRetentions {
  record Candidate(String tenant, String taskId) {}

  record Projection(
      long recordVersion,
      long todoVersion,
      String binding,
      String bindingDigest,
      String state,
      Instant retainUntil) {}

  record BindingVersions(int version, int todoVersion) {}

  record Body(String field, String value) {}

  record Content(String id, List<Body> bodies) {}

  record RetainedBody(String key, String sha256, long bytes) {}

  interface LockedTask {
    Projection projection();

    String state();

    boolean writersQuiescent();

    boolean unsafeReservations();

    boolean activeInvocations();

    boolean dispatchedModels();

    List<Body> bodies();

    List<Content> steps();

    List<Content> invocations();

    List<Content> dispatches();

    void purgeStep(String id, String tombstone);

    void purgeInvocation(String id, String tombstone);

    void purgeDispatch(String id, String tombstone);

    void purgeTask(String tombstone);

    void markPurged(String retained);

    void event();

    int releaseHold();

    void releaseBudget();
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  Instant now();

  String expectedBinding();

  BindingVersions bindingVersions(String binding);

  String metadata(List<RetainedBody> bodies);
}
