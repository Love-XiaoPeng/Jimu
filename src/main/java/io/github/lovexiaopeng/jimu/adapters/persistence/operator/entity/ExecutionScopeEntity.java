package io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 全复合键只读投影；写入使用原子Mapper语句。 */
@TableName(value = "evaluation_execution_scope", autoResultMap = true)
public final class ExecutionScopeEntity {
  @TableField("tenant_id")
  private Object tenantId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  @TableField("run_id")
  private Object runId;

  public Object getRunId() {
    return runId;
  }

  public void setRunId(Object value) {
    runId = value;
  }

  @TableField("record_version")
  private Object recordVersion;

  public Object getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Object value) {
    recordVersion = value;
  }

  @TableField("scope_snapshot")
  private Object scopeSnapshot;

  public Object getScopeSnapshot() {
    return scopeSnapshot;
  }

  public void setScopeSnapshot(Object value) {
    scopeSnapshot = value;
  }

  @TableField("scope_digest")
  private Object scopeDigest;

  public Object getScopeDigest() {
    return scopeDigest;
  }

  public void setScopeDigest(Object value) {
    scopeDigest = value;
  }

  @TableField("bound_at_utc")
  private Object boundAtUtc;

  public Object getBoundAtUtc() {
    return boundAtUtc;
  }

  public void setBoundAtUtc(Object value) {
    boundAtUtc = value;
  }

  @TableField("bound_by")
  private Object boundBy;

  public Object getBoundBy() {
    return boundBy;
  }

  public void setBoundBy(Object value) {
    boundBy = value;
  }
}
