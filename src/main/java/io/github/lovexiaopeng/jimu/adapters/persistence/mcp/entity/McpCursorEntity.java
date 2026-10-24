package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 按原root集合谓词删除；不将tenant/task/token复合主键伪造为单列by-id。 */
@TableName("mcp_cursor")
public final class McpCursorEntity {
  @TableField("tenant_id")
  private Object tenantId;

  @TableField("task_id")
  private Object taskId;

  @TableField("root_invocation_id")
  private Object rootInvocationId;

  public Object getTenantId() {
    return tenantId;
  }

  public void setTenantId(Object value) {
    tenantId = value;
  }

  public Object getTaskId() {
    return taskId;
  }

  public void setTaskId(Object value) {
    taskId = value;
  }

  public Object getRootInvocationId() {
    return rootInvocationId;
  }

  public void setRootInvocationId(Object value) {
    rootInvocationId = value;
  }
}
