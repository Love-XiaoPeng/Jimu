package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.function.Consumer;

/** 请求私有返回句柄；事务借出前预留，实际序列化结束/取消后清引用，仅退款一次。 */
public final class SessionTaskResponse implements AutoCloseable {
  public interface Budget {
    void retain(Object owner, long bytes);

    void release(Object owner);

    void wire(int count);

    /** 同一请求的绝对截止与首错，不另开计量包络。 */
    default void checkpoint() {}

    default RuntimeException failure(RuntimeException cause) {
      return cause;
    }

    default <T> T primitive(String name, int count, java.util.function.Supplier<T> action) {
      wire(count);
      return action.get();
    }
  }

  private Budget budget;
  private Tasks.View view;
  private long bytes;
  private boolean producing = true, consuming, closing, released;
  private RuntimeException firstFailure;

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
    if (closing && !consuming) release();
  }

  public synchronized String taskId() {
    available();
    return java.util.Objects.requireNonNull(view).taskId();
  }

  /** 短锁借用；外部I/O不占生命周期锁，归还前仍持有View及其预算。 */
  public void write(Consumer<Tasks.View> consumer) {
    Tasks.View borrowed;
    synchronized (this) {
      available();
      if (producing || consuming || view == null)
        throw new IllegalStateException("Response not ready");
      consuming = true;
      borrowed = view;
    }
    try {
      consumer.accept(borrowed);
    } catch (RuntimeException failure) {
      throw failed(failure);
    } catch (Error failure) {
      failed(new IllegalStateException("Task response failed", failure));
      throw failure;
    } finally {
      borrowed = null;
      synchronized (this) {
        consuming = false;
        closing = true;
        release();
      }
    }
  }

  public void wire(int count) {
    Budget context = consumerBudget();
    try {
      context.wire(count);
      checkpoint();
    } catch (RuntimeException failure) {
      throw failed(failure);
    }
  }

  public <T> T primitive(String name, int count, java.util.function.Supplier<T> action) {
    Budget context = consumerBudget();
    try {
      T result = context.primitive(name, count, action);
      checkpoint();
      return result;
    } catch (RuntimeException failure) {
      throw failed(failure);
    }
  }

  private synchronized Budget consumerBudget() {
    available();
    if (!consuming) throw new IllegalStateException("Response consumer lease required");
    return budget;
  }

  private synchronized void checkpoint() {
    available();
  }

  private synchronized RuntimeException failed(RuntimeException failure) {
    if (firstFailure == null) firstFailure = budget == null ? failure : budget.failure(failure);
    return firstFailure;
  }

  private void available() {
    if (firstFailure != null) throw firstFailure;
    if (budget != null) budget.checkpoint();
    if (closing || released) throw failed(new TaskFailure(504, "SESSION_QUERY_TIMEOUT"));
  }

  @Override
  public synchronized void close() {
    closing = true;
    // 超时不能在生产者仍借用View/局部列表时提前退款。
    if (!producing && !consuming) release();
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
