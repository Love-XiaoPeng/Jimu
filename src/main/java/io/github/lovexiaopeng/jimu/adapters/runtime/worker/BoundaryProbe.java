package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit test assembly only: the test supervisor owns the child PID and the barrier directory.
 */
public final class BoundaryProbe {
  private final String boundary;
  private final Path root;
  private final AtomicBoolean used = new AtomicBoolean();

  public BoundaryProbe(String boundary, Path root) {
    this.boundary = boundary;
    this.root = root;
  }

  public void hit(String point, String taskId) {
    if (!point.equals(boundary) || !used.compareAndSet(false, true)) return;
    try {
      Files.writeString(
          root.resolve("reached"), point + " " + taskId + " " + ProcessHandle.current().pid());
      while (!Files.exists(root.resolve("release"))) Thread.sleep(25);
    } catch (IOException e) {
      throw new IllegalStateException("Test barrier failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Test barrier interrupted", e);
    }
  }
}
