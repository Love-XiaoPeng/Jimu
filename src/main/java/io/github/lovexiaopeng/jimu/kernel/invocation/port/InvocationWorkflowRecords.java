package io.github.lovexiaopeng.jimu.kernel.invocation.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** 调用者原事务内的原槽、安装pin、证明与具名写入；不暴露SQL或另建账本。 */
public interface InvocationWorkflowRecords {
  record Slot(
      String id,
      String effect,
      String tool,
      String provider,
      String inputDigest,
      String state,
      String output,
      String outputDigest) {}

  record Pin(
      String version,
      String digest,
      long epoch,
      String pinnedVersion,
      String pinnedDigest,
      long pinnedEpoch,
      String installedVersion,
      String installedDigest) {}

  interface Admission {
    Slot slot();

    void freezeDirectory();

    void validateMaterial();

    Pin pin();

    long toolsUsed();

    long maxTools();

    long tenantTools();

    long tenantToolLimit();

    void reserveUnit();

    void markRunning();

    void dispatched();

    void commitFence();
  }

  interface Success {
    String tool();

    void validateResultProof();

    int succeed();

    void settleUnit();

    void succeeded();

    void commitFence();

    void validateMaterial();

    void stopEvaluationRun();
  }

  Admission lockAdmission(Lease lease, String step, Call call, String version, String digest);

  Success lockSuccess(Lease lease, String invocation, String output);
}
