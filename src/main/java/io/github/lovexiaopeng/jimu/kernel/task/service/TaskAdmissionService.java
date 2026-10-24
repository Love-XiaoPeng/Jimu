package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.List;

/** 普通与历史会话受理共用的原子用例；历史来源仅由既有受信端口提供。 */
public interface TaskAdmissionService {
  Tasks.View submit(
      Tasks.Identity identity,
      String key,
      Tasks.Request request,
      List<TaskImages.Input> images,
      List<String> historySources,
      String callerId);
}
