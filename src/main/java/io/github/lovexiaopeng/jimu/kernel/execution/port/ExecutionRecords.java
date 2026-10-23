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

  interface Admission {
    Optional<StoredStep> latest();

    void validateMaterial(String request, int ordinal);

    boolean unfinishedInvocations(String step);

    void validateModuleRequest(String request);

    void reserveMedia(String step, String request);

    void insertStep(String step, int ordinal, String request);

    void prepareProjections(String step, String request);

    void preparedEvent(String step);

    void commitFence();

    void validateSummary(String summary);

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
