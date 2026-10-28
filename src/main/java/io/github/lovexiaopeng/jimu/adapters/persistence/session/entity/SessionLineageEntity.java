package io.github.lovexiaopeng.jimu.adapters.persistence.session.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** lineage复合键实体；没有虚构单字段主键，正文仅在一次插入和单向清理时写入。 */
@TableName("task_session_lineage")
public final class SessionLineageEntity {
  private String tenantId;
  private String taskId;
  private Integer recordVersion;
  private String genesisSnapshot;
  private String genesisDigest;
  private Long genesisBytes;
  private String graphSnapshot;
  private String graphDigest;
  private Long graphBytes;
  private String contentState;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    tenantId = value;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(String value) {
    taskId = value;
  }

  public Integer getRecordVersion() {
    return recordVersion;
  }

  public void setRecordVersion(Integer value) {
    recordVersion = value;
  }

  public String getGenesisSnapshot() {
    return genesisSnapshot;
  }

  public void setGenesisSnapshot(String value) {
    genesisSnapshot = value;
  }

  public String getGenesisDigest() {
    return genesisDigest;
  }

  public void setGenesisDigest(String value) {
    genesisDigest = value;
  }

  public Long getGenesisBytes() {
    return genesisBytes;
  }

  public void setGenesisBytes(Long value) {
    genesisBytes = value;
  }

  public String getGraphSnapshot() {
    return graphSnapshot;
  }

  public void setGraphSnapshot(String value) {
    graphSnapshot = value;
  }

  public String getGraphDigest() {
    return graphDigest;
  }

  public void setGraphDigest(String value) {
    graphDigest = value;
  }

  public Long getGraphBytes() {
    return graphBytes;
  }

  public void setGraphBytes(Long value) {
    graphBytes = value;
  }

  public String getContentState() {
    return contentState;
  }

  public void setContentState(String value) {
    contentState = value;
  }
}
