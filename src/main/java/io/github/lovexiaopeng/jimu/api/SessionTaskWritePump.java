package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.web.context.request.async.DeferredResult;

/** 唯一producer/写callback串行借用；终态线程只记意图，归还后清引用、退款、开MVC闸门。 */
final class SessionTaskWritePump implements WriteListener {
  enum State {
    PRODUCING,
    WAIT_READY,
    DRAINING,
    CLOSING,
    TERMINATED
  }

  final DeferredResult<Object> gate;
  private final long deadline;
  private SessionTaskResponse response;
  private HttpServletResponse http;
  private ServletOutputStream sink;
  private SessionTaskResponse.ConsumerLease lease;
  private SessionTaskJsonCursor cursor;
  private int pendingByte = -2;
  private boolean callbackPending, producerStarted;
  private int active;
  private State state = State.PRODUCING;
  private Throwable terminalFailure;

  SessionTaskWritePump(long deadline, SessionTaskResponse response) {
    this.deadline = deadline;
    this.response = response;
    gate = new DeferredResult<>(Math.max(1, (deadline - System.nanoTime() + 999999) / 1000000));
  }

  synchronized void attach(HttpServletResponse value) {
    if (http != null) throw new IllegalStateException("Submit write cycle already attached");
    http = value;
  }

  void produce(Runnable producer) {
    synchronized (this) {
      if (state != State.PRODUCING || producerStarted) return;
      producerStarted = true;
      active++;
    }
    try {
      checkIntent();
      producer.run();
      response.produced();
      checkIntent();
      // scaffold已在Controller预留；此处先留cursor/lease/primitive及诊断槽再分配。
      lease = response.consume(SessionTaskJsonCursor.WORKSPACE_BYTES);
      cursor = new SessionTaskJsonCursor(response, lease.view());
      checkIntent();
      http.setStatus(202);
      http.setHeader("Location", "/api/tasks/" + response.taskId());
      http.setHeader("Cache-Control", "no-store");
      http.setContentType("application/json");
      sink = http.getOutputStream();
      synchronized (this) {
        if (state != State.CLOSING) state = State.WAIT_READY;
      }
      checkIntent();
      sink.setWriteListener(this); // 恰好一次；同步重入仅登记pending。
    } catch (Throwable failure) {
      terminate(failure);
      if (failure instanceof Error error) throw error;
    } finally {
      producer = null;
      responseProduced();
      returned();
    }
  }

  private void responseProduced() {
    // 仍持有active，timeout不能并发清response或退query owner。
    if (response != null) response.produced();
  }

  void terminate(Throwable failure) {
    boolean finish;
    synchronized (this) {
      if (state == State.TERMINATED) return;
      if (state != State.CLOSING) {
        terminalFailure = failure;
        state = State.CLOSING;
      }
      finish = active == 0;
      if (finish) active = 1;
    }
    if (finish) finish();
  }

  private void returned() {
    boolean finish, drive;
    synchronized (this) {
      active--;
      finish = state == State.CLOSING && active == 0;
      drive = !finish && active == 0 && callbackPending;
      if (finish) active = 1;
    }
    if (finish) finish();
    else if (drive) onWritePossible();
  }

  private void finish() {
    // 独占终态借用：先断开所有View/待写byte/sink别名，再恰一次退款，最后完成MVC。
    Throwable failure = terminalFailure;
    if (failure != null) {
      RuntimeException cause =
          failure instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException("Task response failed", failure);
      failure = response.reject(cause);
    }
    if (cursor != null) cursor.close();
    cursor = null;
    pendingByte = -2;
    sink = null;
    http = null;
    if (lease != null) lease.close();
    lease = null;
    var completedResponse = response;
    response = null;
    completedResponse.close();
    completedResponse.produced();
    synchronized (this) {
      terminalFailure = null;
      callbackPending = false;
      active = 0;
      state = State.TERMINATED;
    }
    Throwable result = failure;
    completedResponse.afterRelease(
        () -> {
          if (result == null) gate.setResult(null);
          else gate.setErrorResult(result);
        });
  }

  private synchronized void checkIntent() {
    if (state == State.CLOSING || System.nanoTime() >= deadline) throw timeout();
  }

  static TaskFailure timeout() {
    return new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
  }

  @Override
  public void onWritePossible() {
    synchronized (this) {
      if (state == State.CLOSING || state == State.TERMINATED) return;
      if (active != 0 || state == State.PRODUCING) {
        callbackPending = true;
        return;
      }
      callbackPending = false;
      active++;
      state = State.DRAINING;
    }
    try {
      drain();
    } catch (Throwable failure) {
      terminate(failure);
      if (failure instanceof Error error) throw error;
    } finally {
      synchronized (this) {
        if (state == State.DRAINING) state = State.WAIT_READY;
      }
      returned();
    }
  }

  private void drain() throws IOException {
    for (; ; ) {
      checkIntent();
      serialize(1); // 待写标量条件；暂停后不重编码。
      if (pendingByte == -2) {
        boolean ready = response.primitive("response.serialize", 1, sink::isReady);
        checkIntent();
        serialize(1);
        if (!ready) return;
        int next = cursor.next();
        response.primitive(
            "response.serialize",
            1,
            () -> {
              pendingByte = next;
              return null;
            });
        serialize(1); // EOF比较；不会再次调用已结束的cursor。
        if (pendingByte < 0) {
          terminate(null);
          return;
        }
      }
      // 编码后仍在每个实际write前检查就绪；仅一个int跨暂停持有。
      boolean ready = response.primitive("response.serialize", 1, sink::isReady);
      checkIntent();
      serialize(1);
      if (!ready) return;
      int value = response.primitive("response.serialize", 1, () -> pendingByte);
      response.primitive(
          "response.wire",
          1,
          () -> {
            try {
              sink.write(value);
            } catch (IOException failure) {
              throw new UncheckedIOException(failure);
            }
            return null;
          });
      response.primitive(
          "response.serialize",
          1,
          () -> {
            pendingByte = -2;
            return null;
          }); // 成功写后的标量重置；每byte循环与wire仍逐次申请。
      checkIntent();
    }
  }

  private void serialize(int count) {
    response.primitive("response.serialize", count, () -> null);
  }

  @Override
  public void onError(Throwable failure) {
    terminate(failure);
  }

  synchronized State state() {
    return state;
  }
}
