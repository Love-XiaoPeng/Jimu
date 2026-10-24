package io.github.lovexiaopeng.jimu.kernel.budget.api;

import io.github.lovexiaopeng.jimu.kernel.budget.domain.ModelCosts;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot.Price;

/** 整数价格计算的跨模块契约；舍入、零值及溢出沿用预算域规则。 */
public final class ModelCost {
  private ModelCost() {}

  public static long amount(Price price, long input, long output) {
    return ModelCosts.amount(price, input, output);
  }
}
