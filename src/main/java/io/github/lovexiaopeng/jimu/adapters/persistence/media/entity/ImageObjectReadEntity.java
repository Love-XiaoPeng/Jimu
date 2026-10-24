package io.github.lovexiaopeng.jimu.adapters.persistence.media.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 普通对象身份读取，不伪造tenant/image复合主键。 */
@TableName(value = "task_image_object", autoResultMap = true)
public final class ImageObjectReadEntity {
  @TableField("tenant_id")
  private Object tenantId;

  @TableField("task_id")
  private Object taskId;

  @TableField("image_id")
  private Object imageId;

  @TableField("cleanup_state")
  private Object cleanupState;

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

  public Object getImageId() {
    return imageId;
  }

  public void setImageId(Object value) {
    imageId = value;
  }

  public Object getCleanupState() {
    return cleanupState;
  }

  public void setCleanupState(Object value) {
    cleanupState = value;
  }
}
