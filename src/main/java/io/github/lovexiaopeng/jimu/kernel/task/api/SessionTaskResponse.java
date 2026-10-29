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
  private long preparedBytes, preparedDeadline = Long.MAX_VALUE;
  private boolean producing = true, consuming, closing, released;
  private RuntimeException firstFailure;

  public synchronized void bind(Budget context) {
    available();
    if (budget != null) throw new IllegalStateException("Response already bound");
    long retained = Math.addExact(1024, preparedBytes);
    context.retain(this, retained);
    budget = context;
    bytes = retained;
    preparedBytes = 0;
  }

  /** 请求私有scaffold的分配前预留，bind原query时整体转入同一owner，不重开计量包络。 */
  public synchronized void prepare(long deadline, long extra) {
    available();
    preparedDeadline = Math.min(preparedDeadline, deadline);
    if (budget != null) {
      retain(extra);
      return;
    }
    long next = Math.addExact(preparedBytes, extra);
    // 与原query的65536基座/1MiB scratch相同；尚未读正文、没有事务或授权证明可复用。
    if (Math.addExact(65536 + 1024, next) > 1048576)
      throw failed(new TaskFailure(429, "SESSION_HISTORY_LIMIT"));
    preparedBytes = next;
    available();
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
    try (var lease = consume(0)) {
      try {
        consumer.accept(lease.view());
      } catch (RuntimeException failure) {
        throw failed(failure);
      } catch (Error failure) {
        failed(new IllegalStateException("Task response failed", failure));
        throw failure;
      }
    }
  }

  /** 一次性持有，可跨多个Servlet写回调；先预留lease及编码工作区，再分配句柄。 */
  public ConsumerLease consume(long workspaceBytes) {
    synchronized (this) {
      available();
      if (producing || consuming || view == null)
        throw new IllegalStateException("Response not ready");
      retain(Math.addExact(128, workspaceBytes));
      consuming = true;
      return new ConsumerLease();
    }
  }

  public final class ConsumerLease implements AutoCloseable {
    private boolean returned;

    private ConsumerLease() {}

    public Tasks.View view() {
      synchronized (SessionTaskResponse.this) {
        available();
        if (returned) throw new IllegalStateException("Response lease returned");
        return view;
      }
    }

    @Override
    public void close() {
      synchronized (SessionTaskResponse.this) {
        if (returned) return;
        returned = true;
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

  /** 仅无在途producer或唯一consumer调用，终态线程不并发修改query计量Map。 */
  public RuntimeException reject(RuntimeException failure) {
    return failed(failure);
  }

  private void available() {
    if (firstFailure != null) throw firstFailure;
    if (budget != null) budget.checkpoint();
    else if (System.nanoTime() >= preparedDeadline)
      throw failed(new TaskFailure(504, "SESSION_QUERY_TIMEOUT"));
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
    preparedBytes = 0;
    released = true;
  }
}
