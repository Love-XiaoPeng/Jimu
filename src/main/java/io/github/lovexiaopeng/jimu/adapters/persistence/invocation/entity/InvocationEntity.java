package io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** invocation 的新增记录字段；复合主键显式保留 tenant，不声明单列主键或自动 ID。 */
@TableName("invocation")
public final class InvocationEntity {
  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    tenantId = value;
  }

  @TableField(value = "id")
  private String invocationId;

  public String getInvocationId() {
    return invocationId;
  }

  public void setInvocationId(String value) {
    invocationId = value;
  }

  private String taskId;

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String value) {
    taskId = value;
  }

  private String stepId;

  public String getStepId() {
    return stepId;
  }

  public void setStepId(String value) {
    stepId = value;
  }

  private String attemptId;

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String value) {
    attemptId = value;
  }

  private Integer ordinal;

  public Integer getOrdinal() {
    return ordinal;
  }

  public void setOrdinal(Integer value) {
    ordinal = value;
  }

  private String slot;

  public String getSlot() {
    return slot;
  }

  public void setSlot(String value) {
    slot = value;
  }

  private String providerId;

  public String getProviderId() {
    return providerId;
  }

  public void setProviderId(String value) {
    providerId = value;
  }

  private String effectIntentId;

  public String getEffectIntentId() {
    return effectIntentId;
  }

  public void setEffectIntentId(String value) {
    effectIntentId = value;
  }

  private String tool;

  public String getTool() {
    return tool;
  }

  public void setTool(String value) {
    tool = value;
  }

  private String pluginVersion;

  public String getPluginVersion() {
    return pluginVersion;
  }

  public void setPluginVersion(String value) {
    pluginVersion = value;
  }

  private String pluginDigest;

  public String getPluginDigest() {
    return pluginDigest;
  }

  public void setPluginDigest(String value) {
    pluginDigest = value;
  }

  private Long installationEpoch;

  public Long getInstallationEpoch() {
    return installationEpoch;
  }

  public void setInstallationEpoch(Long value) {
    installationEpoch = value;
  }

  private String inputSnapshot;

  public String getInputSnapshot() {
    return inputSnapshot;
  }

  public void setInputSnapshot(String value) {
    inputSnapshot = value;
  }

  private String inputDigest;

  public String getInputDigest() {
    return inputDigest;
  }

  public void setInputDigest(String value) {
    inputDigest = value;
  }

  private String state;

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }
}
