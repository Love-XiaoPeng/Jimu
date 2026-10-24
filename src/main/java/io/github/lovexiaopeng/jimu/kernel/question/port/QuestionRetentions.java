package io.github.lovexiaopeng.jimu.kernel.question.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.List;

/** 问题保留事实与具名写入；不向内核传递Map或SQL。 */
public interface QuestionRetentions {
  record Candidate(String tenant, String taskId) {}

  record Body(String field, String value) {}

  record Content(String id, List<Body> bodies) {}

  record RetainedBody(String key, String sha256, long bytes) {}

  interface LockedTask {
    String cleanupState();

    Tasks.View view();

    boolean writersQuiescent();

    boolean unsafeReservations();

    boolean unknownInvocations();

    List<Body> bodies();

    List<Content> steps();

    List<Content> invocations();

    List<Content> questions();

    void purgeStep(String id, String tombstone);

    void purgeInvocation(String id, String tombstone);

    void purgeQuestion(String id, String tombstone, String retained);

    void purgeTask(String tombstone, String retained);

    int releaseHold();

    void releaseBudget();

    void event();
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedTask lockTask(Candidate candidate);

  String metadata(List<RetainedBody> bodies);
}
