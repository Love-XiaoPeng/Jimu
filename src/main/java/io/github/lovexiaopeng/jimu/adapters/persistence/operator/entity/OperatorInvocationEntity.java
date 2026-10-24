package io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运维证明固定只读投影，不声明伪单列主键。 */
@TableName(value = "invocation", autoResultMap = true)
public final class OperatorInvocationEntity {
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

  @TableField("step_id")
  private Object stepId;

  public Object getStepId() {
    return stepId;
  }

  public void setStepId(Object value) {
    stepId = value;
  }

  @TableField("attempt_id")
  private Object attemptId;

  public Object getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(Object value) {
    attemptId = value;
  }

  @TableField("effect_intent_id")
  private Object effectIntentId;

  public Object getEffectIntentId() {
    return effectIntentId;
  }

  public void setEffectIntentId(Object value) {
    effectIntentId = value;
  }

  @TableField("slot")
  private Object slot;

  public Object getSlot() {
    return slot;
  }

  public void setSlot(Object value) {
    slot = value;
  }

  @TableField("provider_id")
  private Object providerId;

  public Object getProviderId() {
    return providerId;
  }

  public void setProviderId(Object value) {
    providerId = value;
  }

  @TableField("tool")
  private Object tool;

  public Object getTool() {
    return tool;
  }

  public void setTool(Object value) {
    tool = value;
  }

  @TableField("input_snapshot")
  private Object inputSnapshot;

  public Object getInputSnapshot() {
    return inputSnapshot;
  }

  public void setInputSnapshot(Object value) {
    inputSnapshot = value;
  }

  @TableField("input_digest")
  private Object inputDigest;

  public Object getInputDigest() {
    return inputDigest;
  }

  public void setInputDigest(Object value) {
    inputDigest = value;
  }

  @TableField("plugin_version")
  private Object pluginVersion;

  public Object getPluginVersion() {
    return pluginVersion;
  }

  public void setPluginVersion(Object value) {
    pluginVersion = value;
  }

  @TableField("plugin_digest")
  private Object pluginDigest;

  public Object getPluginDigest() {
    return pluginDigest;
  }

  public void setPluginDigest(Object value) {
    pluginDigest = value;
  }

  @TableField("installation_epoch")
  private Object installationEpoch;

  public Object getInstallationEpoch() {
    return installationEpoch;
  }

  public void setInstallationEpoch(Object value) {
    installationEpoch = value;
  }

  @TableField("output_snapshot")
  private Object outputSnapshot;

  public Object getOutputSnapshot() {
    return outputSnapshot;
  }

  public void setOutputSnapshot(Object value) {
    outputSnapshot = value;
  }

  @TableField("output_digest")
  private Object outputDigest;

  public Object getOutputDigest() {
    return outputDigest;
  }

  public void setOutputDigest(Object value) {
    outputDigest = value;
  }

  @TableField("state")
  private Object state;

  public Object getState() {
    return state;
  }

  public void setState(Object value) {
    state = value;
  }
}
