package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.port.TaskCapabilities;
import java.util.List;

/** 在原身份事务内按安装顺序发现当前可公开的能力。 */
public interface TaskCapabilitiesService {
  List<String> discover(TaskCapabilities records);
}
