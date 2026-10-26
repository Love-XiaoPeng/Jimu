package io.github.lovexiaopeng.jimu.kernel.directory.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import java.util.List;
import java.util.Map;

/** 目录聚合的惰性字段视图及单项持久化、契约端口；不预读原行。 */
public interface DirectoryRecords {
  interface Row {
    String tenantId();

    String id();

    long recordVersion();

    long directoryVersion();

    long pathPolicyVersion();

    String namespaceDigest();

    String bindingSnapshot();

    String bindingDigest();

    long revision();

    String contentState();

    String cwd();

    String workspaceState();

    long installationEpoch();

    String stepTask();

    String stepState();

    long stepVersion();

    String responseSnapshot();

    String responseDigest();

    String inputSnapshot();

    String inputDigest();

    String providerId();

    String tool();

    String pluginVersion();

    String pluginDigest();

    String state();

    long stepOrdinal();

    String baseCwd();

    long baseRevision();

    String stepId();

    long directorySlotIndex();

    String taskId();

    long slotIndex();

    String mode();

    String targetCwd();

    String canonicalPathsSnapshot();

    String canonicalPathsDigest();

    String outputSnapshot();

    String outputDigest();

    long appliedRevision();

    String committedAttemptId();

    long committedFence();

    long changedEventSeq();

    String slot();

    long toolsUsed();

    long maxTools();

    long toolUsed();

    long toolLimit();

    long nextEventSeq();

    java.time.Instant retainUntil();

    String invocationTask();

    String invocationState();

    String retainedDigests();

    String invocationId();

    String attemptId();

    java.time.Instant committedAt();

    Object sourceInvocationIdObject();

    Object appliedRevisionObject();

    Object committedAttemptIdObject();

    Object changedObject();

    Object changedEventSeqObject();

    Object taskIdObject();

    void directorySlotIndex(int value);
  }

  interface Input {
    String cd();
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

  interface Json {
    Json path(String field);

    Json get(int index);

    String textValue();

    String asText();
  }

  <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work);

  <T> T transaction(java.util.function.Supplier<T> work);

  Row fenced(Lease lease, boolean admission);

  boolean directoryTask(Row task);

  Row projectionRow(String tenant, String task);

  void binding(String raw, String namespace);

  ToolSnapshot tools(Row task);

  Response response(String raw);

  Input input(String raw);

  Json inputJson(String raw, int limit);

  void validateFile(String tool, Json input);

  String resolve(String base, String input, boolean directory);

  String json(Object value);

  String hash(String value);

  Json readJson(String value);

  WorkingDirectoryPort.Result output(String raw);

  void view(Map<String, Object> view);

  void directoryStorage(Row task, long bytes);

  void todoCommitFence(Lease lease);

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

  int readyWorkspace(String tenant, String task);

  Row originalDirectoryInvocation(String tenant, String task, String step, String slot);

  Row originalDirectoryTask(String tenant, String task);

  Row priorDirectorySlot(String tenant, String task, String step, String slot);

  List<Row> unfinishedDirectoryStep(String tenant, String task, long ordinal);

  List<Row> existingDirectoryBinding(String tenant, String invocation);

  int insertDirectoryBinding(
      String tenant,
      String invocation,
      String task,
      String step,
      String binding,
      String namespace,
      long slot,
      String mode,
      long revision,
      String cwd,
      String target,
      String paths,
      String digest,
      String input);

  Row frozenDirectoryBinding(String tenant, String invocation);

  List<Row> directorySourceAttempt(String tenant, String task, String attempt, long fence);

  List<Row> directorySourceEvent(String tenant, String task, long seq, String fact);

  Row directoryCommitInvocation(String tenant, String task, String invocation);

  int countDirectoryTool(String tenant, String task);

  int countDirectoryBudget(String tenant);

  int settleDirectoryReservation(String tenant, String invocation);

  int completeDirectoryInvocation(String output, String digest, String tenant, String invocation);

  int completeDirectoryBinding(
      long revision,
      boolean changed,
      String attempt,
      long fence,
      Long seq,
      String proof,
      String tenant,
      String invocation);

  int advanceDirectoryRevision(
      String cwd, long revision, String invocation, String tenant, String task, long base);

  Row directoryFileInvocation(String tenant, String task, String invocation);

  Row directoryOwnerView(String tenant, String task, String actor);

  List<Row> directoryViewEvent(String tenant, String task, long seq, String fact);

  List<Row> directoryViewAttempt(String tenant, String task, String attempt, long fence);
}
