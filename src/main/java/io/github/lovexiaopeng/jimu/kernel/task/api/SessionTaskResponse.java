package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.function.Consumer;

/** 请求私有返回句柄；事务借出前预留，实际序列化结束/取消后清引用，仅退款一次。 */
public final class SessionTaskResponse implements AutoCloseable {
  public interface Budget {
    void retain(Object owner, long bytes);

    void release(Object owner);

    void wire(int count);
  }

  private Budget budget;
  private Tasks.View view;
  private long bytes;
  private boolean producing = true, closing, released;

  public synchronized void bind(Budget context) {
    available();
    if (budget != null) throw new IllegalStateException("Response already bound");
    context.retain(this, 1024);
    budget = context;
    bytes = 1024;
  }

  public synchronized void retain(long extra) {
    available();
    if (budget == null) throw new IllegalStateException("Response query required");
    long next = Math.addExact(bytes, extra);
    budget.retain(this, next);
    bytes = next;
  }

  public synchronized void hold(Tasks.View value) {
    available();
    if (view != null) throw new IllegalStateException("Response already produced");
    view = value;
  }

  public synchronized void produced() {
    producing = false;
    if (closing) release();
  }

  public synchronized String taskId() {
    available();
    return java.util.Objects.requireNonNull(view).taskId();
  }

  /** 序列化同步消费，不向MVC借出View；保留固定编码workspace并沿原累计预算/截止写出。 */
  public synchronized void write(Consumer<Tasks.View> consumer) {
    available();
    if (producing || view == null) throw new IllegalStateException("Response not ready");
    retain(65536);
    try {
      consumer.accept(view);
    } finally {
      closing = true;
      release();
    }
  }

  public synchronized void wire(int count) {
    available();
    budget.wire(count);
  }

  private void available() {
    if (closing || released) throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
  }

  @Override
  public synchronized void close() {
    closing = true;
    // 超时不能在生产者仍借用View/局部列表时提前退款。
    if (!producing) release();
  }

  private void release() {
    if (released) return;
    view = null;
    if (budget != null) budget.release(this);
    budget = null;
    bytes = 0;
    released = true;
  }
}
