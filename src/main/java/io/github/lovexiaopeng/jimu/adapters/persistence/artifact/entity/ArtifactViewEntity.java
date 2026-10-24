package io.github.lovexiaopeng.jimu.adapters.persistence.artifact.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 任务公开视图使用的完整旧投影；无伪单列主键。 */
@TableName(value = "artifact", autoResultMap = true)
public final class ArtifactViewEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("id")
  private Object rowId;

  public Object getRowId() {
    return rowId;
  }

  public void setRowId(Object value) {
    rowId = value;
  }

  @TableField("task_id")
  private Object taskId;

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  @TableField("producer_invocation")
  private Object producerInvocation;

  public Object getProducerInvocation() {
    return producerInvocation;
  }

  public void setProducerInvocation(Object value) {
    producerInvocation = value;
  }

  @TableField("upload_id")
  private Object uploadId;

  public Object getUploadId() {
    return uploadId;
  }

  public void setUploadId(Object value) {
    uploadId = value;
  }

  @TableField("production_fence")
  private Object productionFence;

  public Object getProductionFence() {
    return productionFence;
  }

  public void setProductionFence(Object value) {
    productionFence = value;
  }

  @TableField("state")
  private Object state;

  public Object getState() {
    return state;
  }

  public void setState(Object value) {
    state = value;
  }

  @TableField("snapshot_key")
  private Object snapshotKey;

  public Object getSnapshotKey() {
    return snapshotKey;
  }

  public void setSnapshotKey(Object value) {
    snapshotKey = value;
  }

  @TableField("sha256")
  private Object sha256;

  public Object getSha256() {
    return sha256;
  }

  public void setSha256(Object value) {
    sha256 = value;
  }

  @TableField("size")
  private Object size;

  public Object getSize() {
    return size;
  }

  public void setSize(Object value) {
    size = value;
  }

  @TableField("media_type")
  private Object mediaType;

  public Object getMediaType() {
    return mediaType;
  }

  public void setMediaType(Object value) {
    mediaType = value;
  }

  @TableField("created_at")
  private Object createdAt;

  public Object getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Object value) {
    createdAt = value;
  }

  @TableField("updated_at")
  private Object updatedAt;

  public Object getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Object value) {
    updatedAt = value;
  }

  @TableField("expires_at")
  private Object expiresAt;

  public Object getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Object value) {
    expiresAt = value;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Object value) {
    recordVersion = value;
  }

  @TableField("file_receipt")
  private Object fileReceipt;

  public Object getFileReceipt() {
    return fileReceipt;
  }

  public void setFileReceipt(Object value) {
    fileReceipt = value;
  }

  @TableField("receipt_digest")
  private Object receiptDigest;

  public Object getReceiptDigest() {
    return receiptDigest;
  }

  public void setReceiptDigest(Object value) {
    receiptDigest = value;
  }
}
