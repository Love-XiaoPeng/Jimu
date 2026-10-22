package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual tracked thread deliberately ignores interruption; no real codec hang is claimed. */
public final class ImageRuntimeScenario {
  public static void main(String[] args) throws Exception {
    for (boolean revoke : List.of(false, true)) {
      var operations = new OperationThreads();
      var release = new CountDownLatch(1);
      var entered = new CountDownLatch(1);
      var stopped = new AtomicInteger();
      var writes = new AtomicInteger();
      ArtifactStore store =
          (ArtifactStore)
              Proxy.newProxyInstance(
                  ArtifactStore.class.getClassLoader(),
                  new Class<?>[] {ArtifactStore.class},
                  (proxy, method, values) -> {
                    if (method.getName().equals("check")) return null;
                    writes.incrementAndGet();
                    throw new AssertionError("No storage IO after cancellation");
                  });
      ExecutionLedger ledger =
          (ExecutionLedger)
              Proxy.newProxyInstance(
                  ExecutionLedger.class.getClassLoader(),
                  new Class<?>[] {ExecutionLedger.class, ImageLedger.class},
                  (proxy, method, values) -> {
                    return switch (method.getName()) {
                      case "imageInput" ->
                          new ImageLedger.Raw(
                              new TaskImages.Entry("input", "inputs/images/a.png", "raw", 1),
                              new byte[] {1});
                      case "reserveImage" -> UUID.randomUUID().toString();
                      case "checkpoint" -> {
                        if (revoke && entered.getCount() == 0)
                          throw new TaskFailure(409, "INSTALLATION_CHANGED");
                        yield Optional.empty();
                      }
                      case "imageDecodeTimedOut" -> {
                        stopped.incrementAndGet();
                        yield null;
                      }
                      default ->
                          throw new AssertionError("Unexpected ledger call " + method.getName());
                    };
                  });
      var runtime =
          new ImageRuntime(
              store,
              operations,
              (path, raw, cancellation) -> {
                entered.countDown();
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException ignored) {
                    /* Models a codec that ignores interruption. */
                  }
                }
                return new ImageNormalizer.Normalized(new byte[] {1}, 1, 1, 1, 1);
              });
      var lease =
          new ExecutionLedger.Lease(
              "tenant",
              "owner",
              UUID.randomUUID().toString(),
              "attempt",
              1,
              "worker",
              Instant.now().plusMillis(300),
              "goal",
              List.of("read_image"),
              1);
      try {
        runtime
            .bind(
                ledger, lease, new ExecutionLedger.Invocation("invocation", null, "RUNNING", null))
            .read("inputs/images/a.png");
        throw new AssertionError("Expected cancellation");
      } catch (TaskFailure failure) {
        if (!failure.code().equals(revoke ? "INSTALLATION_CHANGED" : "IMAGE_DECODE_TIMEOUT"))
          throw failure;
        if (operations.activeCount() != 1 || stopped.get() == 0 || writes.get() != 0)
          throw new AssertionError(
              "Future.cancel must retain actual active operation and request supervised stop");
        System.out.println(
            "PASS "
                + failure.code()
                + " retains actual active decoder until exit; zero storage writes");
      } finally {
        release.countDown();
        operations.awaitDrained();
      }
      if (operations.activeCount() != 0) throw new AssertionError("Actual exit required");
      System.out.println("PASS actual decoder exit drains operation");
    }
  }
}
