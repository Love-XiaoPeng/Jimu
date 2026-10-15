package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LedgerMaintenance implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final ArtifactStore store;
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

  public LedgerMaintenance(ExecutionLedger ledger, ArtifactStore store) {
    this.ledger = ledger;
    this.store = store;
  }

  public void start() {
    clock.scheduleWithFixedDelay(
        () -> {
          try {
            ledger.cleanupArtifacts(store);
          } catch (RuntimeException error) {
            System.getLogger(LedgerMaintenance.class.getName())
                .log(System.Logger.Level.ERROR, "Artifact reconciliation failed", error);
          }
        },
        0,
        10,
        TimeUnit.SECONDS);
  }

  @Override
  public void close() throws InterruptedException {
    clock.shutdownNow();
    if (!clock.awaitTermination(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Maintenance did not stop");
  }
}
