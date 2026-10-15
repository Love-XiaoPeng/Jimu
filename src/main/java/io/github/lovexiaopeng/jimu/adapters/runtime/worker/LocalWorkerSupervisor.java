package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-host P2 supervisor: PID plus process-start receipt prevents signalling a reused PID. */
public final class LocalWorkerSupervisor implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final String host;
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

  public LocalWorkerSupervisor(ExecutionLedger ledger, String host) {
    this.ledger = ledger;
    this.host = host;
  }

  public void start() {
    clock.scheduleWithFixedDelay(this::reconcile, 0, 250, TimeUnit.MILLISECONDS);
  }

  private void reconcile() {
    try {
      for (var receipt : ledger.processes(host)) {
        var process = ProcessHandle.of(receipt.pid());
        if (process.isEmpty() || !process.get().isAlive()) {
          ledger.processStopped(receipt.id());
          continue;
        }
        long ticks;
        try {
          ticks = LinuxProcessReceipt.startTicks(receipt.pid());
        } catch (java.io.IOException unavailable) {
          // Unreadable /proc is not proof of death. Recheck on the next reconciliation.
          continue;
        }
        boolean same = ticks == receipt.startTicks();
        if (same && process.get().isAlive() && receipt.cancellationOverdue()) {
          // Registered local workers only. No process-name scans and no shared-service operations.
          process.get().destroyForcibly();
          process.get().onExit().get(5, TimeUnit.SECONDS);
        }
        if (!same || !process.get().isAlive()) ledger.processStopped(receipt.id());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException
        | RuntimeException error) {
      System.getLogger(LocalWorkerSupervisor.class.getName())
          .log(System.Logger.Level.ERROR, "Worker reconciliation failed", error);
    }
  }

  @Override
  public void close() throws InterruptedException {
    clock.shutdownNow();
    if (!clock.awaitTermination(6, TimeUnit.SECONDS))
      throw new IllegalStateException("Supervisor did not stop");
  }
}
