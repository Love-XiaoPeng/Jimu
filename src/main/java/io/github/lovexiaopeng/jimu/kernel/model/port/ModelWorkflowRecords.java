package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;

/** 调用者原事务内的类型化模型事实和具名写入；不建立第二套账本。 */
public interface ModelWorkflowRecords {
  interface SnapshotAccess {
    ModelSnapshot model();

    void checkEvaluation(ModelSnapshot snapshot);
  }

  SnapshotAccess lockModelSnapshot(Lease lease);

  ToolSnapshot lockToolSnapshot(Lease lease);

  record Limits(
      boolean paid,
      long inputUpper,
      int maxOutput,
      long reservedCost,
      boolean runPresent,
      String runState,
      long requests,
      long maxRequests,
      long runRoom,
      long taskCostRoom,
      long tenantCostRoom,
      long dispatches,
      long modelsUsed,
      long maxModels,
      long tenantModels,
      long tenantModelLimit,
      long tenantTokens,
      long upperTenantTokens,
      long taskTokens,
      long maxTokens) {}

  record Request(
      String snapshot,
      String body,
      String bodyDigest,
      String configDigest,
      String actualConfigDigest) {}

  record Outcome(String state, String code) {}

  record PlanUsage(String phase, String sample, String model, long calls, long cost) {}

  record PlanAllowance(
      String phase,
      String sample,
      String model,
      int candidates,
      long phaseCostLimit,
      List<PlanUsage> usage) {
    public PlanAllowance {
      usage = List.copyOf(usage);
    }
  }

  interface Admission {
    void validateStep();

    boolean responded();

    void validateDirectory();

    Request request();

    void validateEvaluationAndMaterial();

    void validateInput(String body);

    Limits limits();

    PlanAllowance planAllowance();

    boolean activePlanDispatch();

    void reserveModuleStorage();

    void reserveCost(String dispatch, long cost);

    void insertDispatch(String dispatch, String bodyDigest, long cost);

    void reserveTokens(String dispatch, long upper);

    void markRunning(String dispatch);

    void dispatched(String dispatch);

    void commitFence();
  }

  interface Response {
    boolean originalDispatch();

    ModelCostRecords costs();

    String costRejection();

    boolean constrained();

    void validatePayload(String response, List<Call> calls);

    long rejectedPayloadUpper();

    long reservedTokens();

    void settleTokens(long tokens, long released);

    void holdUnknown();

    void rejectStep();

    void recordRejection(String code);

    void rejected(String code);

    boolean question();

    void rejectOversizedQuestion();

    void reserveQuestionStorage(long bytes);

    void storeResponse(String response);

    String modelRejection();

    String runRejection();

    void requireScope();

    int invocationCount();

    long todoRevision();

    void insertCall(Call call, int ordinal, long todoRevision);

    void bindCalls();

    void responded();
  }

  Admission lockAdmission(Lease lease, String step);

  Response lockResponse(Lease lease, String step, String dispatch);

  Outcome outcome(Lease lease, String dispatch);
}
