package io.github.lovexiaopeng.jimu.kernel.budget.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class BudgetErrorCode {
  public static final String RESERVATION_STATE = "RESERVATION_STATE";
  public static final String STORAGE_BUDGET_EXCEEDED = "STORAGE_BUDGET_EXCEEDED";

  private BudgetErrorCode() {}
}
