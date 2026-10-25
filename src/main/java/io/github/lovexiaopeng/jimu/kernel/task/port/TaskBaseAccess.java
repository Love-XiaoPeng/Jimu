package io.github.lovexiaopeng.jimu.kernel.task.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;

/** 原任务行的惰性字段、类型判断及已有资格回调；不提前解码或合并锁。 */
public interface TaskBaseAccess {
  String tenant();

  String owner();

  List<String> capabilities();

  long epoch(Identity identity, List<String> capabilities);

  boolean sessionTask();

  void sessionAccess();

  boolean directoryTask();

  void directoryAccess();

  boolean todoTask();

  void todoAccess();

  boolean questionTask();

  void questionAccess();

  long installationEpoch();

  boolean hasMcpSnapshot();

  boolean hasMcpDigest();

  void mcpBinding();
}
