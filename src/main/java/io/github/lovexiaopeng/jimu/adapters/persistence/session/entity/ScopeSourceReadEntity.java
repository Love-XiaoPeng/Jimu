package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 来源资格只读MP投影，tenant＋id完整集合键。 */
@TableName("task")
public final class ScopeSourceReadEntity {
  @TableField("tenant_id")
  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }

  @TableField("id")
  private String rowId;

  public String getRowId() {
    return rowId;
  }

  @TableField("owner_actor_id")
  private String ownerActorId;

  public String getOwnerActorId() {
    return ownerActorId;
  }

  @TableField("capabilities")
  private String capabilities;

  public String getCapabilities() {
    return capabilities;
  }

  @TableField("tools_digest")
  private String toolsDigest;

  public String getToolsDigest() {
    return toolsDigest;
  }

  @TableField("installation_epoch")
  private Long installationEpoch;

  public Long getInstallationEpoch() {
    return installationEpoch;
  }

  @TableField("state")
  private String state;

  public String getState() {
    return state;
  }

  @TableField("created_at")
  private java.time.LocalDateTime createdAt;

  public java.time.LocalDateTime getCreatedAt() {
    return createdAt;
  }

  @TableField("request_digest")
  private String requestDigest;

  public String getRequestDigest() {
    return requestDigest;
  }
}
