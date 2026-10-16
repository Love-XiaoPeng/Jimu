package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All mutations are short, fenced transactions. No connection escapes this port. */
public interface ExecutionLedger {
  record Lease(
      String tenant,
      String actor,
      String taskId,
      String attemptId,
      long fence,
      String worker,
      Instant deadline,
      String goal,
      List<String> capabilities,
      long installationEpoch) {
    public Lease {
      capabilities = List.copyOf(capabilities);
    }
  }

  record Step(String id, int ordinal, String request, String response, String dispatchId) {}

  record Call(String slot, String providerId, String tool, String input) {}

  record Invocation(String id, String effectId, String state, String output) {}

  record PluginPin(String version, String digest, long installationEpoch) {}

  record ToolSnapshot(int schemaVersion, Map<String, PluginPin> pins) {
    public ToolSnapshot {
      pins = Map.copyOf(pins);
    }
  }

  record Upload(String id, String uploadId, String state, String hash, long size, String key) {}

  Optional<Lease> claim(String worker);

  boolean renew(Lease lease);

  Optional<Step> checkpoint(Lease lease);

  ModelSnapshot modelSnapshot(Lease lease);

  ToolSnapshot toolSnapshot(Lease lease);

  Step prepare(Lease lease, String request);

  String dispatchModel(Lease lease, String stepId);

  void modelResponded(
      Lease lease,
      String stepId,
      String dispatchId,
      String response,
      List<Call> calls,
      Integer tokens,
      ModelUsage usage);

  void modelFailed(Lease lease, String dispatchId, String code, ModelUsage usage);

  Invocation admit(
      Lease lease, String stepId, Call call, String pluginVersion, String pluginDigest);

  void toolSucceeded(Lease lease, String invocationId, String output);

  void uncertain(Lease lease, String invocationId);

  Upload stage(Lease lease, String hash, long size);

  void verified(Lease lease, String artifactId, String key);

  void publish(Lease lease, String artifactId);

  void finish(Lease lease, String summary);

  void stop(Lease lease, String code);

  void check();

  void cleanupArtifacts(ArtifactStore store);

  record WorkerProcess(String id, long pid, long startTicks, boolean cancellationOverdue) {}

  void registerWorker(String worker, String host, long pid, long startTicks);

  List<WorkerProcess> processes(String host);

  void processStopped(String worker);

  void quiescent(Lease lease);
}
