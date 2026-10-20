package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class WorkerLoop implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final Consumer<ExecutionLedger.Lease> execution;
  private final OperationThreads operations;
  private volatile boolean undrained;
  private final String instance = UUID.randomUUID().toString();
  private final String host;
  private final ResourceProbe resources;
  private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

  public WorkerLoop(
      ExecutionLedger ledger,
      DurableAgent agent,
      String host,
      ResourceProbe resources,
      OperationThreads operations) {
    this(ledger, agent::run, host, resources, operations);
  }

  WorkerLoop(
      ExecutionLedger ledger,
      Consumer<ExecutionLedger.Lease> execution,
      String host,
      ResourceProbe resources,
      OperationThreads operations) {
    this.ledger = ledger;
    this.execution = execution;
    this.operations = operations;
    this.host = host;
    this.resources = resources;
  }

  public void start() throws java.io.IOException {
    var process = ProcessHandle.current();
    ledger.registerWorker(
        instance, host, process.pid(), LinuxProcessReceipt.startTicks(process.pid()));
    work.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      // An interrupted drain is not proof of quiescence and must never admit another attempt.
      if (undrained || operations.activeCount() != 0) return;
      if (!resources.ready()) return;
      var candidate = ledger.claim(instance);
      if (candidate.isEmpty()) return;
      var lease = candidate.get();
      var renewal =
          heartbeat.scheduleAtFixedRate(() -> ledger.renew(lease), 10, 10, TimeUnit.SECONDS);
      try {
        execution.accept(lease);
      } catch (RuntimeException error) {
        String code = error instanceof TaskFailure failure ? failure.code() : "EXECUTION_FAILED";
        System.getLogger(WorkerLoop.class.getName())
            .log(System.Logger.Level.WARNING, "Execution stopped: " + code, error);
        try {
          ledger.stop(lease, code);
        } catch (TaskFailure rejected) {
          if (!"FENCE_REJECTED".equals(rejected.code())) throw rejected;
        }
      } finally {
        try {
          operations.awaitDrained();
          ledger.quiescent(lease);
        } catch (InterruptedException interrupted) {
          undrained = true;
          Thread.currentThread().interrupt();
        } finally {
          renewal.cancel(false);
        }
      }
    } catch (RuntimeException error) {
      System.getLogger(WorkerLoop.class.getName())
          .log(System.Logger.Level.ERROR, "Worker transaction failed", error);
    }
  }

  @Override
  public void close() throws InterruptedException {
    work.shutdownNow();
    heartbeat.shutdownNow();
    if (!work.awaitTermination(25, TimeUnit.SECONDS)
        || !heartbeat.awaitTermination(5, TimeUnit.SECONDS)
        || undrained
        || operations.activeCount() != 0) throw new IllegalStateException("Worker did not stop");
  }
}
