package io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运维证明固定只读投影，不声明伪单列主键。 */
@TableName(value = "task", autoResultMap = true)
public final class OperatorTaskEntity {
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

  @TableField("request_snapshot")
  private Object requestSnapshot;

  public Object getRequestSnapshot() {
    return requestSnapshot;
  }

  public void setRequestSnapshot(Object value) {
    requestSnapshot = value;
  }

  @TableField("request_digest")
  private Object requestDigest;

  public Object getRequestDigest() {
    return requestDigest;
  }

  public void setRequestDigest(Object value) {
    requestDigest = value;
  }

  @TableField("model_snapshot")
  private Object modelSnapshot;

  public Object getModelSnapshot() {
    return modelSnapshot;
  }

  public void setModelSnapshot(Object value) {
    modelSnapshot = value;
  }

  @TableField("model_digest")
  private Object modelDigest;

  public Object getModelDigest() {
    return modelDigest;
  }

  public void setModelDigest(Object value) {
    modelDigest = value;
  }

  @TableField("tools_snapshot")
  private Object toolsSnapshot;

  public Object getToolsSnapshot() {
    return toolsSnapshot;
  }

  public void setToolsSnapshot(Object value) {
    toolsSnapshot = value;
  }

  @TableField("tools_digest")
  private Object toolsDigest;

  public Object getToolsDigest() {
    return toolsDigest;
  }

  public void setToolsDigest(Object value) {
    toolsDigest = value;
  }
}
