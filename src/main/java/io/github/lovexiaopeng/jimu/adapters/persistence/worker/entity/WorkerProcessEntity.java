package io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 单键宿主进程收据；数据库默认值和观察坐标系保持原契约。 */
@TableName("worker_process")
public final class WorkerProcessEntity {
  @TableId(type = IdType.INPUT)
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String value) {
    id = value;
  }

  private String hostId;

  public String getHostId() {
    return hostId;
  }

  public void setHostId(String value) {
    hostId = value;
  }

  private Long pid;

  public Long getPid() {
    return pid;
  }

  public void setPid(Long value) {
    pid = value;
  }

  private Long startTicks;

  public Long getStartTicks() {
    return startTicks;
  }

  public void setStartTicks(Long value) {
    startTicks = value;
  }

  private Boolean stopped;

  public Boolean getStopped() {
    return stopped;
  }

  public void setStopped(Boolean value) {
    stopped = value;
  }

  private java.time.LocalDateTime registeredAt;

  public java.time.LocalDateTime getRegisteredAt() {
    return registeredAt;
  }

  public void setRegisteredAt(java.time.LocalDateTime value) {
    registeredAt = value;
  }

  private String bootId;

  public String getBootId() {
    return bootId;
  }

  public void setBootId(String value) {
    bootId = value;
  }

  private String observerPidNamespace;

  public String getObserverPidNamespace() {
    return observerPidNamespace;
  }

  public void setObserverPidNamespace(String value) {
    observerPidNamespace = value;
  }
}
