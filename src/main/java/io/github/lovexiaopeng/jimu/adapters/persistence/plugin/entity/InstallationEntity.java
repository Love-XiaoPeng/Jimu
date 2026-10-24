package io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 安装复合键的普通查询投影；不声明单列主键，不自动填充或修改安装状态。 */
@TableName("installation")
public final class InstallationEntity {
  private String tenantId;
  private String pluginId;
  private Boolean enabled;
  private Long epoch;
  private String pluginVersion;
  private String pluginDigest;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    tenantId = value;
  }

  public String getPluginId() {
    return pluginId;
  }

  public void setPluginId(String value) {
    pluginId = value;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean value) {
    enabled = value;
  }

  public Long getEpoch() {
    return epoch;
  }

  public void setEpoch(Long value) {
    epoch = value;
  }

  public String getPluginVersion() {
    return pluginVersion;
  }

  public void setPluginVersion(String value) {
    pluginVersion = value;
  }

  public String getPluginDigest() {
    return pluginDigest;
  }

  public void setPluginDigest(String value) {
    pluginDigest = value;
  }
}
