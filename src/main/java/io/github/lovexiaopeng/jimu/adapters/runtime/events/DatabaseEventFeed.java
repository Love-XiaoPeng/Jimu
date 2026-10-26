package io.github.lovexiaopeng.jimu.adapters.runtime.events;

import io.github.lovexiaopeng.jimu.kernel.event.api.EventErrorCode;
import io.github.lovexiaopeng.jimu.kernel.event.api.EventFeedPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * At most 32 subscriptions and 2 per actor/task. Each batch is at most 64 fixed small envelopes.
 */
public final class DatabaseEventFeed implements EventFeed, AutoCloseable {
  private final Tasks tasks;
  private final Authenticator auth;
  private final Map<String, Integer> subscriptions = new HashMap<>();
  private final ScheduledExecutorService readers =
      Executors.newScheduledThreadPool(EventFeedPolicyV1.SUBSCRIPTIONS);

  public DatabaseEventFeed(Tasks tasks, Authenticator auth) {
    this.tasks = tasks;
    this.auth = auth;
  }

  @Override
  public AutoCloseable subscribe(
      String authorization,
      String taskId,
      long cursor,
      Consumer<Tasks.Event> event,
      Runnable complete) {
    var identity = auth.authenticate(authorization);
    tasks.events(identity, taskId, cursor, EventFeedPolicyV1.BATCH);
    String key = identity.tenant() + ":" + identity.actor() + ":" + taskId;
    synchronized (subscriptions) {
      if (subscriptions.values().stream().mapToInt(Integer::intValue).sum()
              >= EventFeedPolicyV1.SUBSCRIPTIONS
          || subscriptions.getOrDefault(key, 0) >= EventFeedPolicyV1.PER_ACTOR_TASK)
        throw new TaskFailure(429, EventErrorCode.SUBSCRIPTION_LIMIT);
      subscriptions.merge(key, 1, Integer::sum);
    }
    AtomicBoolean closed = new AtomicBoolean();
    var handle = new AtomicReference<ScheduledFuture<?>>();
    Runnable close =
        () -> {
          if (closed.compareAndSet(false, true)) {
            var future = handle.get();
            if (future != null) future.cancel(false);
            synchronized (subscriptions) {
              subscriptions.compute(key, (k, count) -> count == 1 ? null : count - 1);
            }
            complete.run();
          }
        };
    long[] last = {cursor};
    long started = System.nanoTime();
    handle.set(
        readers.scheduleWithFixedDelay(
            () -> {
              if (closed.get()) return;
              try {
                var current = auth.authenticate(authorization);
                for (var item : tasks.events(current, taskId, last[0], EventFeedPolicyV1.BATCH)) {
                  tasks.events(auth.authenticate(authorization), taskId, last[0], 1);
                  event.accept(item);
                  last[0] = item.seq();
                }
                if ((tasks.get(current, taskId).terminal()
                        && tasks.events(current, taskId, last[0], 1).isEmpty())
                    || System.nanoTime() - started
                        > TimeUnit.SECONDS.toNanos(EventFeedPolicyV1.LIFETIME_SECONDS)) close.run();
              } catch (RuntimeException failure) {
                close.run();
              }
            },
            EventFeedPolicyV1.INITIAL_DELAY_MILLIS,
            EventFeedPolicyV1.POLL_MILLIS,
            TimeUnit.MILLISECONDS));
    return close::run;
  }

  @Override
  public void close() throws InterruptedException {
    readers.shutdownNow();
    if (!readers.awaitTermination(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Subscriptions did not stop");
  }
}
