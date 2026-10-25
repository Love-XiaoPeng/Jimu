package io.github.lovexiaopeng.jimu.kernel.todo.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** 待办资格原位置惰性取值；安装行只在比较到达时解码单列。 */
public interface TodoEligibility {
  List<String> capabilities();

  PluginPin pin();

  Installation installation();

  ToolDescriptor descriptor();

  void projection();

  interface Installation {
    String version();

    String digest();

    long epoch();
  }
}
