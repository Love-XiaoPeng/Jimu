package io.github.lovexiaopeng.jimu.kernel.task.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.List;

/** 普通任务准入及 owner 用例，身份只能来自受信认证端口。 */
public interface TaskService {
  Tasks.View submit(Tasks.Identity identity, String key, TaskSubmission request);

  Tasks.View get(Tasks.Identity identity, String taskId);

  Tasks.View cancel(Tasks.Identity identity, String taskId);

  List<String> capabilities(Tasks.Identity identity);
}
