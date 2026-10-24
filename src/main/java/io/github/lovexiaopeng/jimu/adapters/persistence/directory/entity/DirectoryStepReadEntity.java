package io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 原全租户/任务读取的显式投影；不声明伪单列主键。 */
@TableName("step")
public final class DirectoryStepReadEntity {
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

  @TableField("response_snapshot")
  private Object responseSnapshot;

  public Object getResponseSnapshot() {
    return responseSnapshot;
  }

  public void setResponseSnapshot(Object value) {
    responseSnapshot = value;
  }

  @TableField("directory_cwd")
  private Object directoryCwd;

  public Object getDirectoryCwd() {
    return directoryCwd;
  }

  public void setDirectoryCwd(Object value) {
    directoryCwd = value;
  }
}
