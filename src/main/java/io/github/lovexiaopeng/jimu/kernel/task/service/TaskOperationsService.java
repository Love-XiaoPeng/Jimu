package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskOperationsRecords.Row;
import java.util.List;

/** 持久任务观测、取消与事件回放；HTTP和直接账本调用共用唯一实现。 */
public interface TaskOperationsService {
  Tasks.View get(Tasks.Identity identity, String taskId);

  Tasks.View cancel(Tasks.Identity identity, String taskId);

  List<Tasks.Event> events(Tasks.Identity identity, String taskId, long after, int limit);

  Tasks.View view(Row row);

  void terminal(Row row, String state, String code);

  void closeQuestions(Row row, String code);
}
