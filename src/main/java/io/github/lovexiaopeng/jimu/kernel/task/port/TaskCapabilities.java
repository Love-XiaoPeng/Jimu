package io.github.lovexiaopeng.jimu.kernel.task.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;
import java.util.function.Supplier;

/** 发现用例的原事务、身份、安装清单及逐候选惰性资格；不缓存开关或模型。 */
public interface TaskCapabilities {
  <T> T transaction(Supplier<T> work);

  void identity();

  List<String> installed();

  boolean mcpEnabled();

  boolean questionsEnabled();

  boolean directoryAvailable();

  boolean todoAvailable();

  boolean sessionAvailable(boolean search);

  boolean traceAvailable();

  boolean priorAvailable();

  boolean searchAvailable();

  boolean imagesAvailable();

  ModelSnapshot selected();

  boolean imageModelEnabled(ModelSnapshot model);
}
