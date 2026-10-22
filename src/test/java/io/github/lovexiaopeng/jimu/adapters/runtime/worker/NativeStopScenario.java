package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeProcesses;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeRunner;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Real proc/kill observations; a ledger spy observes the supervisor's write boundary. */
public final class NativeStopScenario {
  public static void main(String[] args) throws Exception {
    var bundle = new NativeRunner.Bundle(args[1], args[2], args[3], args[4]);
    var target =
        new ProcessIdentity(Long.parseLong(args[5]), Long.parseLong(args[6]), args[7], args[8]);
    var operations = new OperationThreads();
    var runtime = new NativeSearchRuntime(Path.of(args[0]), bundle, operations);
    var runner = new NativeRunner(bundle, operations);
    var identity = new NativeIdentity(target, target, target);
    if (new NativeSearchRuntime().reconcile(identity, true, true))
      throw new AssertionError("Disabled recovery declared native exit");
    var run =
        new NativeSearchLedger.Run(
            "t",
            "task",
            "invocation",
            "effect",
            "attempt",
            "worker",
            args[0],
            "snapshot",
            "STARTED",
            identity,
            false);
    var stops = new AtomicInteger();
    var workerStops = new AtomicInteger();
    var refunds = new AtomicInteger();
    var ledger =
        (ExecutionLedger)
            Proxy.newProxyInstance(
                ExecutionLedger.class.getClassLoader(),
                new Class<?>[] {ExecutionLedger.class, NativeSearchLedger.class},
                (proxy, method, values) -> {
                  return switch (method.getName()) {
                    case "processes" ->
                        List.of(
                            new ExecutionLedger.WorkerProcess(
                                "worker", target.pid(), target.startTicks(), false));
                    case "expiredSearches" -> List.of();
                    case "nativeSearches" -> List.of(run);
                    case "searchStopped" -> {
                      stops.incrementAndGet();
                      yield null;
                    }
                    case "processStopped" -> {
                      workerStops.incrementAndGet();
                      yield null;
                    }
                    case "searchStorageRemoved" -> {
                      refunds.incrementAndGet();
                      yield null;
                    }
                    default ->
                        throw new AssertionError("Unexpected ledger write: " + method.getName());
                  };
                });
    var self = NativeProcesses.identity(ProcessHandle.current().pid());
    var reused =
        new ProcessIdentity(self.pid(), self.startTicks() + 1, self.bootId(), self.pidNamespace());
    if (!runner.stopped(reused))
      throw new AssertionError("Stale ticks must not match live identity");
    runner.signal(new NativeIdentity(reused, null, null), true);
    if (runner.stopped(self)) throw new AssertionError("Reused identity was signalled");
    var wrongNamespace = new ProcessIdentity(self.pid(), self.startTicks(), self.bootId(), "0");
    try {
      runner.signal(new NativeIdentity(wrongNamespace, null, null), true);
      throw new AssertionError("Namespace mismatch accepted");
    } catch (IOException conservative) {
      System.out.println("PASS namespace mismatch remains unproven");
    }
    try (var supervisor = new LocalWorkerSupervisor(ledger, "host", runtime)) {
      System.out.println("READY");
      var input = new BufferedReader(new InputStreamReader(System.in));
      for (String command; (command = input.readLine()) != null; ) {
        if (command.equals("live")) {
          try {
            if (NativeProcesses.stopped(target))
              throw new AssertionError("Static API falsely stopped live process");
          } catch (IOException conservative) {
            System.out.println("PASS conservative static observation");
          }
          try {
            if (runner.stopped(target))
              throw new AssertionError("Runner falsely stopped live process");
          } catch (IOException conservative) {
            System.out.println("PASS conservative runner observation");
          }
          if (runner.absent(target.pid()))
            throw new AssertionError("Kernel falsely proved absence");
          supervisor.reconcile();
          if (stops.get() != 0 || workerStops.get() != 0 || refunds.get() != 0)
            throw new AssertionError("Premature stop/refund");
          System.out.println("PASS live: no STOPPED, no refund");
        } else if (command.equals("exited")) {
          if (!runner.stopped(target)) throw new AssertionError("Reaped process exit unproven");
          supervisor.reconcile();
          if (stops.get() != 1 || workerStops.get() != 1 || refunds.get() != 0)
            throw new AssertionError("Exit receipt boundary");
          operations.awaitDrained();
          if (operations.activeCount() != 0) throw new AssertionError("Drainers remain active");
          System.out.println("PASS exited: one STOPPED, no early refund");
        } else if (command.startsWith("denied ")) {
          try {
            runner.absent(Long.parseLong(command.substring(7)));
            throw new AssertionError("EPERM was accepted as absence/presence");
          } catch (IOException conservative) {
            System.out.println("PASS EPERM unproven");
          }
        } else throw new AssertionError(command);
      }
    }
  }
}
