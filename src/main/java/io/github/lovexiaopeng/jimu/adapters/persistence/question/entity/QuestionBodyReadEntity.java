package io.github.lovexiaopeng.jimu.adapters.persistence.question.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** 本用例实际消费的正文与完整集合谓词；无伪单列主键。 */
@TableName("user_question")
public final class QuestionBodyReadEntity {
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

  @TableField("answer_snapshot")
  private Object answerSnapshot;

  public Object getAnswerSnapshot() {
    return answerSnapshot;
  }

  public void setAnswerSnapshot(Object value) {
    answerSnapshot = value;
  }

  @TableField("receipt_snapshot")
  private Object receiptSnapshot;

  public Object getReceiptSnapshot() {
    return receiptSnapshot;
  }

  public void setReceiptSnapshot(Object value) {
    receiptSnapshot = value;
  }
}
