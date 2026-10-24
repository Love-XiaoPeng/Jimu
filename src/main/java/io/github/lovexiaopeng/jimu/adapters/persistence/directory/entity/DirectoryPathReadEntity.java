package io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原全租户/任务读取的显式投影；不声明伪单列主键。 */
@TableName("invocation_path_binding")
public final class DirectoryPathReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  @TableField("invocation_id")
  private Object invocationId;

  public Object getInvocationId() {
    return invocationId;
  }

  public void setInvocationId(Object value) {
    invocationId = value;
  }

  @TableField("base_cwd")
  private Object baseCwd;

  public Object getBaseCwd() {
    return baseCwd;
  }

  public void setBaseCwd(Object value) {
    baseCwd = value;
  }

  @TableField("target_cwd")
  private Object targetCwd;

  public Object getTargetCwd() {
    return targetCwd;
  }

  public void setTargetCwd(Object value) {
    targetCwd = value;
  }

  @TableField("canonical_paths_snapshot")
  private Object canonicalPathsSnapshot;

  public Object getCanonicalPathsSnapshot() {
    return canonicalPathsSnapshot;
  }

  public void setCanonicalPathsSnapshot(Object value) {
    canonicalPathsSnapshot = value;
  }

  @TableField("directory_evidence_snapshot")
  private Object directoryEvidenceSnapshot;

  public Object getDirectoryEvidenceSnapshot() {
    return directoryEvidenceSnapshot;
  }

  public void setDirectoryEvidenceSnapshot(Object value) {
    directoryEvidenceSnapshot = value;
  }
}
