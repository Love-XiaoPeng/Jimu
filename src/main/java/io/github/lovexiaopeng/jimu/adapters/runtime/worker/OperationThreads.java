package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

/** Worker-owned operations remain live until their Runnable actually exits, even after cancel. */
public final class OperationThreads {
  private int active;

  /** A native slot is released only after external process-tree death has been proved. */
  public synchronized void nativeStarted() {
    active++;
  }

  public synchronized void nativeStopped() {
    exited();
  }

  public synchronized Thread start(String name, Runnable operation) {
    active++;
    try {
      return Thread.ofVirtual()
          .name(name)
          .start(
              () -> {
                try {
                  operation.run();
                } finally {
                  exited();
                }
              });
    } catch (RuntimeException | Error failure) {
      exited();
      throw failure;
    }
  }

  private synchronized void exited() {
    active--;
    notifyAll();
  }

  public synchronized int activeCount() {
    return active;
  }

  public synchronized void awaitDrained() throws InterruptedException {
    while (active != 0) wait();
  }
}
