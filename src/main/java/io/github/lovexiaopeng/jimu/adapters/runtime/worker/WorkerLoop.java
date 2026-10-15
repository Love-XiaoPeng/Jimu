package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WorkerLoop implements AutoCloseable {
  private final ExecutionLedger ledger;
  private final DurableAgent agent;
  private final String instance = UUID.randomUUID().toString();
  private final String host;
  private final ResourceProbe resources;
  private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

  public WorkerLoop(
      ExecutionLedger ledger, DurableAgent agent, String host, ResourceProbe resources) {
    this.ledger = ledger;
    this.agent = agent;
    this.host = host;
    this.resources = resources;
  }

  public void start() throws java.io.IOException {
    var process = ProcessHandle.current();
    ledger.registerWorker(
        instance, host, process.pid(), LinuxProcessReceipt.startTicks(process.pid()));
    work.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
  }

  private void tick() {
    try {
      if (!resources.ready()) return;
      var candidate = ledger.claim(instance);
      if (candidate.isEmpty()) return;
      var lease = candidate.get();
      var renewal =
          heartbeat.scheduleAtFixedRate(() -> ledger.renew(lease), 10, 10, TimeUnit.SECONDS);
      try {
        agent.run(lease);
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
        renewal.cancel(false);
        ledger.quiescent(lease);
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
        || !heartbeat.awaitTermination(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Worker did not stop");
  }
}
