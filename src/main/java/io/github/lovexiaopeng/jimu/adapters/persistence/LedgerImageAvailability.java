package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageNormalizer;
import io.github.lovexiaopeng.jimu.kernel.artifact.port.ImageAvailability;
import java.util.function.BooleanSupplier;

/**
 * Preserve separate lazy reads of the private Ledger store; codec implementation stays unchanged.
 */
final class LedgerImageAvailability implements ImageAvailability {
  private final BooleanSupplier storePresent;
  private final Runnable storeCheck;

  LedgerImageAvailability(BooleanSupplier storePresent, Runnable storeCheck) {
    this.storePresent = storePresent;
    this.storeCheck = storeCheck;
  }

  public boolean storePresent() {
    return storePresent.getAsBoolean();
  }

  public void checkStore() {
    storeCheck.run();
  }

  public void checkCodec() {
    ImageNormalizer.available();
  }
}
