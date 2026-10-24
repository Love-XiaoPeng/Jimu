package io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("task_todo_projection")
public final class TodoProjectionReadEntity {
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

  @TableField("todo_version")
  private Object todoVersion;

  public Object getTodoVersion() {
    return todoVersion;
  }

  public void setTodoVersion(Object value) {
    todoVersion = value;
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

  @TableField("content_state")
  private Object contentState;

  public Object getContentState() {
    return contentState;
  }

  public void setContentState(Object value) {
    contentState = value;
  }

  @TableField("retain_until")
  private Object retainUntil;

  public Object getRetainUntil() {
    return retainUntil;
  }

  public void setRetainUntil(Object value) {
    retainUntil = value;
  }
}
