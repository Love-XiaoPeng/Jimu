package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeProcesses;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-host P2 supervisor: PID plus process-start receipt prevents signalling a reused PID. */
public final class LocalWorkerSupervisor implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final String host;
  private final Path proc;
  private final NativeSearchRuntime nativeSearch;
  private final java.util.Map<String, Long> nativeStops = new java.util.HashMap<>();
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

  public LocalWorkerSupervisor(ExecutionLedger ledger, String host) {
    this(ledger, host, Path.of("/proc"));
  }

  public LocalWorkerSupervisor(
      ExecutionLedger ledger, String host, NativeSearchRuntime nativeSearch) {
    this.ledger = ledger;
    this.host = host;
    this.proc = Path.of("/proc");
    this.nativeSearch = nativeSearch;
  }

  LocalWorkerSupervisor(ExecutionLedger ledger, String host, Path proc) {
    this.ledger = ledger;
    this.host = host;
    this.proc = proc;
    this.nativeSearch = new NativeSearchRuntime();
  }

  public void start() {
    clock.scheduleWithFixedDelay(this::reconcile, 0, 250, TimeUnit.MILLISECONDS);
  }

  void reconcile() {
    try {
      reconcileNative();
      for (var receipt : ledger.processes(host)) {
        // Capture the JDK handle before validating /proc identity, so its own native
        // start-time check also protects destroyForcibly against subsequent PID reuse.
        var process = ProcessHandle.of(receipt.pid());
        LinuxProcessReceipt.Status status;
        try {
          status =
              LinuxProcessReceipt.read(proc.resolve(Long.toString(receipt.pid())).resolve("stat"));
        } catch (java.nio.file.NoSuchFileException absent) {
          try {
            // Native recovery also relies on this worker/drainer exit receipt. When native
            // search is enabled, it must use the same independent proof as the native tree.
            boolean gone =
                nativeSearch.available()
                    ? nativeSearch.absent(receipt.pid())
                    : ProcessHandle.of(receipt.pid()).isEmpty();
            if (gone) ledger.processStopped(receipt.id());
          } catch (java.io.IOException unproven) {
            // Keep the registered worker and attempt non-quiescent.
          }
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

  private void reconcileNative() {
    if (!(ledger instanceof NativeSearchLedger searches)) return;
    var workers = ledger.processes(host);
    for (var expired : searches.expiredSearches(host)) {
      try {
        try {
          io.github.lovexiaopeng.jimu.adapters.runtime.search.SearchFiles.removeTree(
              Path.of(expired.directory()));
        } catch (java.nio.file.NoSuchFileException alreadyRemoved) {
          /* Retry a completed physical delete before its DB receipt. */
        }
        searches.searchStorageRemoved(expired);
      } catch (java.io.IOException unavailable) {
        /* Retain quota until physical cleanup is proved. */
      }
    }
    for (var run : searches.nativeSearches(host)) {
      try {
        boolean workerGone = true;
        for (var worker : workers)
          if (worker.id().equals(run.worker())) {
            try {
              var status = LinuxProcessReceipt.status(worker.pid());
              workerGone = status.startTicks() != worker.startTicks() || status.exited();
            } catch (java.nio.file.NoSuchFileException absent) {
              workerGone = nativeSearch.absent(worker.pid());
            }
            break;
          }
        var identity = run.identity();
        if (identity == null) {
          try {
            identity = NativeProcesses.started(Path.of(run.directory()), run.effect()).identity();
          } catch (java.nio.file.NoSuchFileException pending) {
            if (workerGone || run.stopRequested()) {
              var launch = NativeProcesses.launch(Path.of(run.directory()), run.effect());
              long since = nativeStops.computeIfAbsent(run.invocation(), key -> System.nanoTime());
              nativeSearch.reconcile(
                  new NativeSearchLedger.NativeIdentity(launch.launcher(), null, null),
                  true,
                  System.nanoTime() - since > TimeUnit.SECONDS.toNanos(3));
            }
            continue; // No namespace identity is not evidence of no namespace.
          }
        }
        boolean stop = workerGone || run.stopRequested();
        long since =
            stop ? nativeStops.computeIfAbsent(run.invocation(), key -> System.nanoTime()) : 0;
        boolean stopped =
            nativeSearch.reconcile(
                identity, stop, stop && System.nanoTime() - since > TimeUnit.SECONDS.toNanos(3));
        if (workerGone && stopped) {
          searches.searchStopped(run, identity);
          nativeStops.remove(run.invocation());
        }
      } catch (java.io.IOException | IllegalArgumentException unavailable) {
        // Unreadable identity keeps the durable native run active and the attempt non-quiescent.
      }
    }
  }

  @Override
  public void close() throws InterruptedException {
    clock.shutdownNow();
    if (!clock.awaitTermination(6, TimeUnit.SECONDS))
      throw new IllegalStateException("Supervisor did not stop");
  }
}
