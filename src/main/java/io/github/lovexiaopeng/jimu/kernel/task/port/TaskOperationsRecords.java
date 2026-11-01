package io.github.lovexiaopeng.jimu.kernel.task.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.time.Instant;
import java.util.List;

/** 查询/取消同一事务内的类型化事实；行句柄只在当前UoW使用。 */
public interface TaskOperationsRecords {
  record Snapshot(
      String id,
      String state,
      long revision,
      Instant createdAt,
      Instant updatedAt,
      Instant cancelRequestedAt,
      String code,
      String summary,
      long nextEventSequence,
      boolean scopeTask,
      boolean historyTask,
      boolean questionTask) {}

  record Question(String id, String invocationId) {}

  interface Row {
    default Tasks.View response(java.util.function.Supplier<Tasks.View> build) {
      return build.get();
    }

    Snapshot snapshot();

    void requireAccess();

    List<Tasks.Artifact> artifacts();

    boolean historyRetained();

    boolean writersQuiescent();

    void requestCancel(boolean changeState);

    void event(String fact, String type, String code);

    Row reload();

    long oldestEvent();

    List<Tasks.Event> events(long after, int limit);

    List<Question> lockQuestions();

    void closeQuestion(Question question, String code, String invocationState);

    void discardQuestionDelivery(String code);

    void clearQuestionContinuation();

    void setTerminal(String state, String code);

    void markUnknownTools();

    void releaseQueue();

    void releaseRun();

    void releaseQuestionTask();

    void holdUnknownModels();

    void stopAttempt(String state);
  }

  boolean scopeHint(Tasks.Identity identity, String taskId);

  void lockBudget(String tenant);

  void lockScope(String tenant, String taskId);

  Row visible(Tasks.Identity identity, String taskId);

  Row lock(String tenant, String taskId);
}
