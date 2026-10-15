package io.github.lovexiaopeng.jimu.adapters.runtime.health;

import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;

public final class LedgerProbe implements ResourceProbe, AutoCloseable {
  private final ExecutionLedger ledger;
  private final ArtifactStore store;
  private final HttpResourceProbe provider;

  public LedgerProbe(ExecutionLedger ledger, ArtifactStore store, HttpResourceProbe provider) {
    this.ledger = ledger;
    this.store = store;
    this.provider = provider;
  }

  @Override
  public boolean ready() {
    try {
      ledger.check();
      store.check();
      return provider == null || provider.ready();
    } catch (RuntimeException failure) {
      return false;
    }
  }

  @Override
  public void close() {
    if (provider != null) provider.close();
  }
}
