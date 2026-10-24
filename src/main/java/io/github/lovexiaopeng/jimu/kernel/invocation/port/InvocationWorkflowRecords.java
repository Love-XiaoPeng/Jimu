package io.github.lovexiaopeng.jimu.kernel.invocation.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;

/** 调用者原事务内的原槽、安装pin、证明与具名写入；不暴露SQL或另建账本。 */
public interface InvocationWorkflowRecords {
  record MaterialUse(
      java.util.Set<String> sources,
      String target,
      String url,
      String filePath,
      int fileCount,
      String presentedPath) {}

  record ImageProof(java.util.List<String> committedIds, String outputId) {}

  record WebProof(String storedOutput, String storedDigest, String receipt) {}

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

    MaterialUse materialUse();

    boolean hasSuccessfulRead();

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

    ImageProof imageProof();

    WebProof webProof();

    String webProofDigest();

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
