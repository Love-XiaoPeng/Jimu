package io.github.lovexiaopeng.jimu.kernel.directory.service;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.Map;

/** 目录快照、槽资格、冻结、提交与来源视图的完整账本用例。 */
public interface DirectoryPersistenceService {
  Row directoryProjection(Row task);

  io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Snapshot directorySnapshot(
      Lease lease);

  void directoryReady(Lease lease);

  Row originalDirectorySlot(Lease lease, String stepId, Call call, String version, String digest);

  Row freezeDirectorySlot(Row task, Row i);

  io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Frozen freezeDirectory(
      Lease lease, String stepId, Call call, String version, String digest);

  void directoryResultSource(Row task, Row i, Row f);

  io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort.Result commitDirectory(
      Lease lease,
      String invocationId,
      io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger.Evidence evidence);

  String directoryFilePath(Lease lease, String invocationId);

  Map<String, Object> getWorkingDirectory(Identity identity, String taskId);
}
