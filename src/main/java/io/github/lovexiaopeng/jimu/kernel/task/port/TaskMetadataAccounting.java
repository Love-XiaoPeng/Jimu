package io.github.lovexiaopeng.jimu.kernel.task.port;

import java.util.List;
import java.util.Map;

/** 单表原始行与原 UTF8 适配；构造不读取 task，表名仅限封闭枚举。 */
public interface TaskMetadataAccounting {
  enum Table {
    TASK,
    TASK_DIRECTORY,
    INVOCATION_PATH_BINDING,
    TASK_TODO_PROJECTION,
    TASK_ATTEMPT,
    STEP,
    INVOCATION,
    MODEL_DISPATCH,
    RESERVATION,
    EVENT
  }

  List<Map<String, Object>> rows(Table table);

  int bytes(String value);
}
