package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkerSupervisorTest {
  @TempDir Path proc;

  private List<String> reconcile(String state, long ticks) throws Exception {
    long pid = ProcessHandle.current().pid();
    Path directory = Files.createDirectory(proc.resolve(Long.toString(pid)));
    if (state != null) {
      if ("unreadable".equals(state)) Files.createDirectory(directory.resolve("stat"));
      else
        Files.writeString(
            directory.resolve("stat"),
            pid + " (worker ) name) " + state + " 0".repeat(18) + " " + ticks + " 0");
    }
    var stopped = new ArrayList<String>();
    var ledger =
        (ExecutionLedger)
            Proxy.newProxyInstance(
                ExecutionLedger.class.getClassLoader(),
                new Class<?>[] {ExecutionLedger.class},
                (proxy, method, args) -> {
                  if ("processes".equals(method.getName())) {
                    assertEquals("local-host", args[0]);
                    return List.of(new ExecutionLedger.WorkerProcess("worker", pid, 123, false));
                  }
                  if ("processStopped".equals(method.getName())) {
                    stopped.add((String) args[0]);
                    return null;
                  }
                  throw new AssertionError(method.getName());
                });
    try (var supervisor = new LocalWorkerSupervisor(ledger, "local-host", proc)) {
      supervisor.reconcile();
    }
    return stopped;
  }

  @Test
  void matchingZombieConfirmsExitWithoutParentReap() throws Exception {
    assertEquals(List.of("worker"), reconcile("Z", 123));
  }

  @Test
  void matchingKernelDeadStateConfirmsExit() throws Exception {
    assertEquals(List.of("worker"), reconcile("X", 123));
  }

  @Test
  void mismatchedStartTicksRemainUnknown() throws Exception {
    assertEquals(List.of(), reconcile("Z", 456));
  }

  @Test
  void missingStatForLiveProcessRemainsUnknown() throws Exception {
    assertEquals(List.of(), reconcile(null, 123));
  }

  @Test
  void unreadableStatRemainsUnknown() throws Exception {
    assertEquals(List.of(), reconcile("unreadable", 123));
  }

  @Test
  void liveProcessDoesNotConfirmExit() throws Exception {
    assertEquals(List.of(), reconcile("S", 123));
  }
}
