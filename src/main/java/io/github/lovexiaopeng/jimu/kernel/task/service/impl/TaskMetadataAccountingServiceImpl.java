package io.github.lovexiaopeng.jimu.kernel.task.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskMetadataAccountingService;
import io.github.lovexiaopeng.jimu.kernel.todo.api.TodoPolicyV1;
import io.github.lovexiaopeng.jimu.plugin.api.TodoErrorCode;
import java.util.List;

/** 原表序列、排除集和逐值计费；全部扫描完才按 closing 阈值拒绝。 */
public final class TaskMetadataAccountingServiceImpl implements TaskMetadataAccountingService {
  public void directoryMetadata(TaskMetadataAccounting records, boolean closing) {
    // UTF-8 scalar values plus eight bytes per column and 32 per row conservatively
    // bound fixed metadata. Body copies are counted separately by todoStorage.
    long bytes = 0;
    var body =
        java.util.Set.of(
            "request_snapshot",
            "goal",
            "summary",
            "model_snapshot",
            "tools_snapshot",
            "binding_snapshot",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "retained_digests",
            "filesystem_receipt",
            "directory_cwd",
            "cwd",
            "base_cwd",
            "target_cwd",
            "canonical_paths_snapshot",
            "directory_evidence_snapshot");
    for (var table :
        List.of(
            Table.TASK,
            Table.TASK_DIRECTORY,
            Table.INVOCATION_PATH_BINDING,
            Table.TASK_ATTEMPT,
            Table.STEP,
            Table.INVOCATION,
            Table.MODEL_DISPATCH,
            Table.RESERVATION,
            Table.EVENT)) {
      for (var row : records.rows(table)) {
        bytes += 32;
        for (var entry : row.entrySet())
          if (!body.contains(entry.getKey()))
            bytes +=
                8 + (entry.getValue() == null ? 1 : records.bytes(entry.getValue().toString()));
      }
    }
    if (bytes
        > (closing
            ? DirectoryPolicyV1.CLOSED_METADATA_BYTES
            : DirectoryPolicyV1.OPEN_METADATA_BYTES))
      throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_STORAGE_LIMIT);
  }

  public void todoMetadata(TaskMetadataAccounting records, boolean closing) {
    // UTF-8 scalar values plus eight bytes per column and 32 per row conservatively
    // bound fixed metadata. Body copies are counted separately by todoStorage.
    long bytes = 0;
    var body =
        java.util.Set.of(
            "request_snapshot",
            "goal",
            "summary",
            "model_snapshot",
            "tools_snapshot",
            "binding_snapshot",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "retained_digests");
    for (var table :
        List.of(
            Table.TASK,
            Table.TASK_TODO_PROJECTION,
            Table.TASK_ATTEMPT,
            Table.STEP,
            Table.INVOCATION,
            Table.MODEL_DISPATCH,
            Table.RESERVATION,
            Table.EVENT)) {
      for (var row : records.rows(table)) {
        bytes += 32;
        for (var entry : row.entrySet())
          if (!body.contains(entry.getKey()))
            bytes +=
                8 + (entry.getValue() == null ? 1 : records.bytes(entry.getValue().toString()));
      }
    }
    if (bytes > (closing ? TodoPolicyV1.CLOSED_METADATA_BYTES : TodoPolicyV1.OPEN_METADATA_BYTES))
      throw new TaskFailure(429, TodoErrorCode.TODO_STORAGE_LIMIT);
  }
}
