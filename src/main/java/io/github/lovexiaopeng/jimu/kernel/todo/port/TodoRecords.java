package io.github.lovexiaopeng.jimu.kernel.todo.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import java.util.List;
import java.util.Map;

/** 惰性原行与单项SQL/codec端口，session仅维护原连接设置。 */
public interface TodoRecords {
  interface Row {
    String tool();

    String providerId();

    String inputSnapshot();

    String inputDigest();

    String stepTask();

    String stepState();

    String responseSnapshot();

    String responseDigest();

    long recordVersion();

    long stepVersion();

    String pluginVersion();

    String pluginDigest();

    long installationEpoch();

    long todoExpectedRevision();

    String id();

    String state();

    String outputSnapshot();

    long revision();

    long toolsUsed();

    long maxTools();

    long toolUsed();

    long toolLimit();

    long nextEventSeq();

    String contentState();

    java.time.Instant retainUntil();

    String invocationTask();

    String sourceStepId();

    String invocationStep();

    long todoAppliedRevision();

    String committedAttemptId();

    String todoCommittedAttemptId();

    long committedFence();

    long todoCommittedFence();

    long updatedSeq();

    long todoEventSeq();

    String outputDigest();

    String invocationDigest();

    long pendingCount();

    long inProgressCount();

    long completedCount();

    String sourceInvocationId();

    String attemptId();

    java.time.Instant todoCommittedAt();

    Object todoExpectedRevisionObject();

    Object todoAppliedRevisionObject();

    Object taskIdObject();
  }

  interface WireCall {
    String id();

    String name();

    String arguments();
  }

  interface Response {
    int version();

    List<WireCall> calls();
  }

  <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work);

  <T> T session(Lease lease, java.util.function.Supplier<T> work);

  <T> T transaction(java.util.function.Supplier<T> work);

  Row fenced(Lease lease, boolean admission);

  boolean todoTask(Row task);

  Row todoProjection(Row task);

  void todoResultSource(Row task, Row invocation);

  void todoCommitFence(Lease lease);

  void todoStorage(Row task, long bytes);

  Row bucket(String tenant);

  void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome);

  void event(Row task, String fact, String type, String body);

  Long factSequence(String tenant, String task, String fact);

  Row visible(Identity identity, String task);

  void access(Row task);

  ToolSnapshot tools(Row task);

  Response response(String raw);

  String json(Object value);

  String hash(String value);

  int bytes(String value);

  TodoPort.Result input(String raw);

  TodoPort.Result output(String raw);

  void view(Map<String, Object> value);

  List<Row> todoCandidates(String tenant, String task, String step, String slot);

  int countTodoTool(String tenant, String task);

  int countTodoBudget(String tenant);

  int settleTodoReservation(String tenant, String invocation);

  int completeTodoInvocation(
      String output,
      String digest,
      long revision,
      String attempt,
      long fence,
      long seq,
      String tenant,
      String invocation);

  int advanceTodoRevision(
      String invocation,
      String step,
      String attempt,
      long fence,
      long seq,
      int pending,
      int inProgress,
      int completed,
      String digest,
      String tenant,
      String task,
      long expected);

  Row todoOwnerView(String tenant, String task, String actor);
}
