package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-host P2 supervisor: PID plus process-start receipt prevents signalling a reused PID. */
public final class LocalWorkerSupervisor implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final String host;
  private final Path proc;
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

  public LocalWorkerSupervisor(ExecutionLedger ledger, String host) {
    this(ledger, host, Path.of("/proc"));
  }

  LocalWorkerSupervisor(ExecutionLedger ledger, String host, Path proc) {
    this.ledger = ledger;
    this.host = host;
    this.proc = proc;
  }

  public void start() {
    clock.scheduleWithFixedDelay(this::reconcile, 0, 250, TimeUnit.MILLISECONDS);
  }

  void reconcile() {
    try {
      for (var receipt : ledger.processes(host)) {
        // Capture the JDK handle before validating /proc identity, so its own native
        // start-time check also protects destroyForcibly against subsequent PID reuse.
        var process = ProcessHandle.of(receipt.pid());
        LinuxProcessReceipt.Status status;
        try {
          status =
              LinuxProcessReceipt.read(proc.resolve(Long.toString(receipt.pid())).resolve("stat"));
        } catch (java.nio.file.NoSuchFileException absent) {
          if (ProcessHandle.of(receipt.pid()).isEmpty()) ledger.processStopped(receipt.id());
          continue;
        } catch (java.io.IOException unavailable) {
          // Unreadable /proc is not proof of death. Recheck on the next reconciliation.
          continue;
        }
        if (status.startTicks() != receipt.startTicks()) continue;
        // Linux zombies have exited and cannot execute, even while a sibling JVM's
        // ProcessHandle reports alive. The external parent need not reap them first.
        if (status.exited()) {
          ledger.processStopped(receipt.id());
          continue;
        }
        if (process.isPresent() && receipt.cancellationOverdue()) {
          // Registered local workers only. No process-name scans and no shared-service operations.
          process.get().destroyForcibly();
        }
      }
    } catch (RuntimeException error) {
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
