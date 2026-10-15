package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.util.function.Consumer;

public interface EventFeed {
  AutoCloseable subscribe(
      String authorization,
      String taskId,
      long cursor,
      Consumer<Tasks.Event> event,
      Runnable complete);
}
