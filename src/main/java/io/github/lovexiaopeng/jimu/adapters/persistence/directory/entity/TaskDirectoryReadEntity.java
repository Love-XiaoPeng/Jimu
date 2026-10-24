package io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原全租户/任务读取的显式投影；不声明伪单列主键。 */
@TableName("task_directory")
public final class TaskDirectoryReadEntity {
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

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Object value) {
    recordVersion = value;
  }

  @TableField("directory_version")
  private Object directoryVersion;

  public Object getDirectoryVersion() {
    return directoryVersion;
  }

  public void setDirectoryVersion(Object value) {
    directoryVersion = value;
  }

  @TableField("path_policy_version")
  private Object pathPolicyVersion;

  public Object getPathPolicyVersion() {
    return pathPolicyVersion;
  }

  public void setPathPolicyVersion(Object value) {
    pathPolicyVersion = value;
  }

  @TableField("binding_snapshot")
  private Object bindingSnapshot;

  public Object getBindingSnapshot() {
    return bindingSnapshot;
  }

  public void setBindingSnapshot(Object value) {
    bindingSnapshot = value;
  }

  @TableField("binding_digest")
  private Object bindingDigest;

  public Object getBindingDigest() {
    return bindingDigest;
  }

  public void setBindingDigest(Object value) {
    bindingDigest = value;
  }

  @TableField("namespace_digest")
  private Object namespaceDigest;

  public Object getNamespaceDigest() {
    return namespaceDigest;
  }

  public void setNamespaceDigest(Object value) {
    namespaceDigest = value;
  }

  @TableField("revision")
  private Object revision;

  public Object getRevision() {
    return revision;
  }

  public void setRevision(Object value) {
    revision = value;
  }

  @TableField("content_state")
  private Object contentState;

  public Object getContentState() {
    return contentState;
  }

  public void setContentState(Object value) {
    contentState = value;
  }

  @TableField("cwd")
  private Object cwd;

  public Object getCwd() {
    return cwd;
  }

  public void setCwd(Object value) {
    cwd = value;
  }

  @TableField("source_invocation_id")
  private Object sourceInvocationId;

  public Object getSourceInvocationId() {
    return sourceInvocationId;
  }

  public void setSourceInvocationId(Object value) {
    sourceInvocationId = value;
  }

  @TableField("workspace_state")
  private Object workspaceState;

  public Object getWorkspaceState() {
    return workspaceState;
  }

  public void setWorkspaceState(Object value) {
    workspaceState = value;
  }

  @TableField("updated_at")
  private Object updatedAt;

  public Object getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Object value) {
    updatedAt = value;
  }

  @TableField("retain_until")
  private Object retainUntil;

  public Object getRetainUntil() {
    return retainUntil;
  }

  public void setRetainUntil(Object value) {
    retainUntil = value;
  }

  @TableField("reserved_bytes")
  private Object reservedBytes;

  public Object getReservedBytes() {
    return reservedBytes;
  }

  public void setReservedBytes(Object value) {
    reservedBytes = value;
  }

  @TableField("cleanup_generation")
  private Object cleanupGeneration;

  public Object getCleanupGeneration() {
    return cleanupGeneration;
  }

  public void setCleanupGeneration(Object value) {
    cleanupGeneration = value;
  }

  @TableField("retained_digests")
  private Object retainedDigests;

  public Object getRetainedDigests() {
    return retainedDigests;
  }

  public void setRetainedDigests(Object value) {
    retainedDigests = value;
  }

  @TableField("filesystem_receipt")
  private Object filesystemReceipt;

  public Object getFilesystemReceipt() {
    return filesystemReceipt;
  }

  public void setFilesystemReceipt(Object value) {
    filesystemReceipt = value;
  }
}
