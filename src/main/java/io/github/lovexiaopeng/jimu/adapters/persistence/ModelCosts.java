package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.math.BigInteger;

/** Integer, upward-rounded micro-CNY accounting; bounds must be evidenced by the operator. */
public final class ModelCosts {
  private ModelCosts() {}

  public static long amount(ModelSnapshot.Price price, long input, long output) {
    return BigInteger.valueOf(input)
        .multiply(BigInteger.valueOf(price.inputMicrosPerMillion()))
        .add(
            BigInteger.valueOf(output).multiply(BigInteger.valueOf(price.outputMicrosPerMillion())))
        .add(BigInteger.valueOf(999999))
        .divide(BigInteger.valueOf(1000000))
        .add(BigInteger.valueOf(price.fixedMicros()))
        .longValueExact();
  }
}
