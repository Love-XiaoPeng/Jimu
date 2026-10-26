package io.github.lovexiaopeng.jimu.kernel.directory.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** 原目录元数据预留空间规则；清理与尚未迁移的目录用例共用，不热改冻结限额。 */
public final class DirectoryStorageLimit {
  private DirectoryStorageLimit() {}

  public static void check(long bytes, long additional) {
    if (bytes + additional + DirectoryPolicyV1.CLOSED_METADATA_BYTES
        > DirectoryPolicyV1.BODY_STORAGE_BYTES)
      throw new TaskFailure(429, DirectoryErrorCode.DIRECTORY_STORAGE_LIMIT);
  }
}
