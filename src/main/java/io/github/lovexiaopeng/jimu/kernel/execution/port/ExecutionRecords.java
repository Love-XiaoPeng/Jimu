package io.github.lovexiaopeng.jimu.kernel.execution.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.Optional;

/** 一次已锁定事务的类型化事实；不让SQL/Map/连接穿过内核边界。 */
public interface ExecutionRecords {
  record StoredStep(
      String id,
      int ordinal,
      String request,
      String response,
      String dispatchId,
      String cleanup,
      long version,
      String requestDigest,
      String responseDigest) {}

  interface Task {
    boolean hasEvaluationRun();

    boolean cancellationRequested();

    boolean pendingEffect();

    void stopEvaluationRun();

    void terminal(String state, String code);
  }

  interface DirectoryRequest {
    int version();

    String requestBody();

    boolean messagesEmpty();

    String lastRole();

    String lastContent();

    String expectedContext();

    String cwd();
  }

  interface MediaBudget {
    long limit();

    long held();

    void hold(long bytes);

    void reserve(String reservation, long bytes, String category, String state, String quality);

    void settle(String reservation);
  }

  interface Admission {
    Optional<StoredStep> latest();

    void validateMaterial(String request, int ordinal);

    boolean unfinishedInvocations(String step);

    boolean question();

    boolean todo();

    boolean directory();

    boolean session();

    long recordVersion();

    java.util.function.Supplier<String> requestBody(String request);

    String publicSummary(String summary);

    io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService.Facts
        questionStorage();

    io.github.lovexiaopeng.jimu.kernel.todo.service.TodoStorageService.Facts todoStorage();

    io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService.Facts
        sessionStorage();

    long directoryStorageBytes();

    DirectoryRequest directoryRequest(String request);

    MediaBudget lockMediaBudget();

    void insertStep(String step, int ordinal, String request);

    void prepareProjections(String step, String request);

    void preparedEvent(String step);

    void commitFence();

    /**
     * Optional retained-session tail guard; legacy checkpoint reads keep their original behavior.
     */
    default void checkpointFence() {}

    boolean pendingQuestions();

    String latestStepId();

    void pauseQuestions(String step);

    boolean publicReport();

    boolean publishedReport();

    boolean unresolvedInvocations();

    boolean unresolvedArtifacts();

    void finishSummary(String summary);

    void terminal(String state, String code);
  }

  Task lockSettlement(Lease lease);

  Admission lockAdmission(Lease lease);
}
