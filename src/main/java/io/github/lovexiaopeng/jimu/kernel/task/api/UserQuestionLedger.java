package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort;
import java.util.List;
import java.util.Map;

public interface UserQuestionLedger {
  record Command(
      int questionVersion, int expectedRevision, List<UserQuestionPort.Answer> answers) {}

  record Submission(boolean replay, Map<String, Object> receipt) {}

  record OpenResult(boolean suspended, UserQuestionPort.AnswerBatch result) {}

  Map<String, Object> listQuestions(Tasks.Identity identity, String taskId);

  Submission answer(
      Tasks.Identity identity, String taskId, String requestId, String key, Command command);

  OpenResult openOrResult(
      ExecutionLedger.Lease lease,
      String step,
      ExecutionLedger.Call call,
      String version,
      String digest);

  /** Pending user messages are consumed only atomically by new model prepare. */
  List<String> pendingQuestionMessages(ExecutionLedger.Lease lease);

  boolean questionContinuation(ExecutionLedger.Lease lease);
}
