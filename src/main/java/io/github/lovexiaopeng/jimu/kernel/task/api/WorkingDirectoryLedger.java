package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import java.util.List;
import java.util.Map;

public interface WorkingDirectoryLedger {
  record Snapshot(String cwd, long revision, String bindingDigest, boolean ready) {}

  record Frozen(
      String invocationId,
      String mode,
      String baseCwd,
      long baseRevision,
      String target,
      List<String> paths,
      String output) {
    public Frozen {
      paths = List.copyOf(paths);
    }
  }

  record Evidence(String namespace, String target, String filesDigest) {}

  Snapshot directorySnapshot(ExecutionLedger.Lease lease);

  void directoryReady(ExecutionLedger.Lease lease);

  Frozen freezeDirectory(
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String version,
      String digest);

  WorkingDirectoryPort.Result commitDirectory(
      ExecutionLedger.Lease lease, String invocationId, Evidence evidence);

  String directoryFilePath(ExecutionLedger.Lease lease, String invocationId);

  Map<String, Object> getWorkingDirectory(Tasks.Identity identity, String taskId);
}
