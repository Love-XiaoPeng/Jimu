package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;

/** 同DS/UoW的目录单项Mapper和codec适配，原Map只惰性读取。 */
final class MapperDirectoryRecords implements DirectoryRecords {
  private final JdbcLedger ledger;

  MapperDirectoryRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  static Row wrap(Map<String, Object> row) {
    return new RowView(row);
  }

  static Map<String, Object> unwrap(Row row) {
    return ((RowView) row).row();
  }

  private record RowView(Map<String, Object> row) implements Row {
    public String tenantId() {
      return (String) row.get("tenant_id");
    }

    public String id() {
      return (String) row.get("id");
    }

    public long recordVersion() {
      return ((Number) row.get("record_version")).longValue();
    }

    public long directoryVersion() {
      return ((Number) row.get("directory_version")).longValue();
    }

    public long pathPolicyVersion() {
      return ((Number) row.get("path_policy_version")).longValue();
    }

    public String namespaceDigest() {
      return (String) row.get("namespace_digest");
    }

    public String bindingSnapshot() {
      return (String) row.get("binding_snapshot");
    }

    public String bindingDigest() {
      return (String) row.get("binding_digest");
    }

    public long revision() {
      return ((Number) row.get("revision")).longValue();
    }

    public String contentState() {
      return (String) row.get("content_state");
    }

    public String cwd() {
      return (String) row.get("cwd");
    }

    public String workspaceState() {
      return (String) row.get("workspace_state");
    }

    public long installationEpoch() {
      return ((Number) row.get("installation_epoch")).longValue();
    }

    public String stepTask() {
      return (String) row.get("step_task");
    }

    public String stepState() {
      return (String) row.get("step_state");
    }

    public long stepVersion() {
      return ((Number) row.get("step_version")).longValue();
    }

    public String responseSnapshot() {
      return (String) row.get("response_snapshot");
    }

    public String responseDigest() {
      return (String) row.get("response_digest");
    }

    public String inputSnapshot() {
      return (String) row.get("input_snapshot");
    }

    public String inputDigest() {
      return (String) row.get("input_digest");
    }

    public String providerId() {
      return (String) row.get("provider_id");
    }

    public String tool() {
      return (String) row.get("tool");
    }

    public String pluginVersion() {
      return (String) row.get("plugin_version");
    }

    public String pluginDigest() {
      return (String) row.get("plugin_digest");
    }

    public String state() {
      return (String) row.get("state");
    }

    public long stepOrdinal() {
      return ((Number) row.get("step_ordinal")).longValue();
    }

    public String baseCwd() {
      return (String) row.get("base_cwd");
    }

    public long baseRevision() {
      return ((Number) row.get("base_revision")).longValue();
    }

    public String stepId() {
      return (String) row.get("step_id");
    }

    public long directorySlotIndex() {
      return ((Number) row.get("directory_slot_index")).longValue();
    }

    public String taskId() {
      return (String) row.get("task_id");
    }

    public long slotIndex() {
      return ((Number) row.get("slot_index")).longValue();
    }

    public String mode() {
      return (String) row.get("mode");
    }

    public String targetCwd() {
      return (String) row.get("target_cwd");
    }

    public String canonicalPathsSnapshot() {
      return (String) row.get("canonical_paths_snapshot");
    }

    public String canonicalPathsDigest() {
      return (String) row.get("canonical_paths_digest");
    }

    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }

    public String outputDigest() {
      return (String) row.get("output_digest");
    }

    public long appliedRevision() {
      return ((Number) row.get("applied_revision")).longValue();
    }

    public String committedAttemptId() {
      return (String) row.get("committed_attempt_id");
    }

    public long committedFence() {
      return ((Number) row.get("committed_fence")).longValue();
    }

    public long changedEventSeq() {
      return ((Number) row.get("changed_event_seq")).longValue();
    }

    public String slot() {
      return (String) row.get("slot");
    }

    public long toolsUsed() {
      return ((Number) row.get("tools_used")).longValue();
    }

    public long maxTools() {
      return ((Number) row.get("max_tools")).longValue();
    }

    public long toolUsed() {
      return ((Number) row.get("tool_used")).longValue();
    }

    public long toolLimit() {
      return ((Number) row.get("tool_limit")).longValue();
    }

    public long nextEventSeq() {
      return ((Number) row.get("next_event_seq")).longValue();
    }

    public java.time.Instant retainUntil() {
      return JdbcLedger.time(row, "retain_until");
    }

    public String invocationTask() {
      return (String) row.get("invocation_task");
    }

    public String invocationState() {
      return (String) row.get("invocation_state");
    }

    public String retainedDigests() {
      return (String) row.get("retained_digests");
    }

    public String invocationId() {
      return (String) row.get("invocation_id");
    }

    public String attemptId() {
      return (String) row.get("attempt_id");
    }

    public java.time.Instant committedAt() {
      return JdbcLedger.time(row, "committed_at");
    }

    public Object sourceInvocationIdObject() {
      return row.get("source_invocation_id");
    }

    public Object appliedRevisionObject() {
      return row.get("applied_revision");
    }

    public Object committedAttemptIdObject() {
      return row.get("committed_attempt_id");
    }

    public Object changedObject() {
      return row.get("changed");
    }

    public Object changedEventSeqObject() {
      return row.get("changed_event_seq");
    }

    public Object taskIdObject() {
      return row.get("task_id");
    }

    public void directorySlotIndex(int value) {
      row.put("directory_slot_index", value);
    }
  }

  private static List<Row> lazy(List<Map<String, Object>> rows) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public Row get(int i) {
        return wrap(rows.get(i));
      }
    };
  }

  private record JsonView(com.fasterxml.jackson.databind.JsonNode node) implements Json {
    public Json path(String field) {
      return new JsonView(node.path(field));
    }

    public Json get(int index) {
      var v = node.get(index);
      return v == null ? null : new JsonView(v);
    }

    public String textValue() {
      return node.textValue();
    }

    public String asText() {
      return node.asText();
    }
  }

  private record CallView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.ToolCall wire)
      implements WireCall {
    public String id() {
      return wire.id();
    }

    public String name() {
      return wire.name();
    }

    public String arguments() {
      return wire.arguments();
    }
  }

  private record ResponseView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response wire)
      implements Response {
    public int version() {
      return wire.version();
    }

    public List<WireCall> calls() {
      var calls = wire.calls();
      return new AbstractList<>() {
        public int size() {
          return calls.size();
        }

        public WireCall get(int i) {
          return new CallView(calls.get(i));
        }
      };
    }
  }

  public <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public <T> T transaction(java.util.function.Supplier<T> work) {
    return ledger.tx(work);
  }

  public Row fenced(Lease lease, boolean admission) {
    return wrap(ledger.fenced(lease, admission));
  }

  public boolean directoryTask(Row task) {
    return ledger.directoryTask(unwrap(task));
  }

  public Row projectionRow(String tenant, String task) {
    return wrap(new MapperDirectoryRetentions(ledger).projectionRow(tenant, task));
  }

  public void binding(String raw, String namespace) {
    ledger.directories.binding(raw, namespace);
  }

  public ToolSnapshot tools(Row task) {
    return ledger.tools(unwrap(task));
  }

  public Response response(String raw) {
    return new ResponseView(
        Payloads.read(
            raw, io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Response.class));
  }

  public Input input(String raw) {
    var input = ledger.directories.input(raw);
    return input::cd;
  }

  public Json inputJson(String raw, int limit) {
    return new JsonView(ledger.directories.json(raw, limit));
  }

  public void validateFile(String tool, Json input) {
    ledger.directoryFileContract(tool).in().validate(((JsonView) input).node());
  }

  public String resolve(String base, String input, boolean directory) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.directory.DirectoryPaths.resolve(
        base, input, directory);
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public Json readJson(String raw) {
    return new JsonView(Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
  }

  public WorkingDirectoryPort.Result output(String raw) {
    return ledger.directories.output(raw);
  }

  public void view(Map<String, Object> view) {
    ledger.directories.view(view);
  }

  public void directoryStorage(Row task, long bytes) {
    ledger.directoryStorage(unwrap(task), bytes);
  }

  public void todoCommitFence(Lease lease) {
    ledger.todoCommitFence(lease);
  }

  public Row bucket(String tenant) {
    return wrap(ledger.bucket(tenant));
  }

  public void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome) {
    ledger.reserve(tenant, task, reservation, dispatch, category, amount, state, outcome);
  }

  public void event(Row task, String fact, String type, String body) {
    ledger.event(unwrap(task), fact, type, body);
  }

  public Long factSequence(String tenant, String task, String fact) {
    return ledger
        .mappers
        .events()
        .selectFactSequenceValue(ledger.queryContext(), tenant, task, fact);
  }

  public Row visible(Identity identity, String task) {
    return wrap(ledger.visible(identity, task));
  }

  public void access(Row task) {
    ledger.access(unwrap(task));
  }

  public int readyWorkspace(String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                .TaskDirectoryReadMapper.class)
        .readyWorkspace(ledger.queryContext(), tenant, task);
  }

  public Row originalDirectoryInvocation(String tenant, String task, String step, String slot) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .DirectoryInvocationReadMapper.class)
                .originalDirectoryInvocation(ledger.queryContext(), tenant, task, step, slot)));
  }

  public Row originalDirectoryTask(String tenant, String task) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class)
                .originalDirectoryTask(ledger.queryContext(), tenant, task)));
  }

  public Row priorDirectorySlot(String tenant, String task, String step, String slot) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .DirectoryInvocationReadMapper.class)
                .priorDirectorySlot(ledger.queryContext(), tenant, task, step, slot)));
  }

  public List<Row> unfinishedDirectoryStep(String tenant, String task, long ordinal) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                    .DirectoryInvocationReadMapper.class)
            .unfinishedDirectoryStep(ledger.queryContext(), tenant, task, ordinal));
  }

  public List<Row> existingDirectoryBinding(String tenant, String invocation) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                    .DirectoryPathReadMapper.class)
            .existingDirectoryBinding(ledger.queryContext(), tenant, invocation));
  }

  public int insertDirectoryBinding(
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
      String input) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                .DirectoryPathReadMapper.class)
        .insertDirectoryBinding(
            ledger.queryContext(),
            tenant,
            invocation,
            task,
            step,
            binding,
            namespace,
            slot,
            mode,
            revision,
            cwd,
            target,
            paths,
            digest,
            input);
  }

  public Row frozenDirectoryBinding(String tenant, String invocation) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .DirectoryPathReadMapper.class)
                .frozenDirectoryBinding(ledger.queryContext(), tenant, invocation)));
  }

  public List<Row> directorySourceAttempt(String tenant, String task, String attempt, long fence) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptMapper
                    .class)
            .directorySourceAttempt(ledger.queryContext(), tenant, task, attempt, fence));
  }

  public List<Row> directorySourceEvent(String tenant, String task, long seq, String fact) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper.EventMapper.class)
            .directorySourceEvent(ledger.queryContext(), tenant, task, seq, fact));
  }

  public Row directoryCommitInvocation(String tenant, String task, String invocation) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .DirectoryInvocationReadMapper.class)
                .directoryCommitInvocation(ledger.queryContext(), tenant, task, invocation)));
  }

  public int countDirectoryTool(String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class)
        .countDirectoryTool(ledger.queryContext(), tenant, task);
  }

  public int countDirectoryBudget(String tenant) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper.class)
        .countDirectoryBudget(ledger.queryContext(), tenant);
  }

  public int settleDirectoryReservation(String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper.class)
        .settleDirectoryReservation(ledger.queryContext(), tenant, invocation);
  }

  public int completeDirectoryInvocation(
      String output, String digest, String tenant, String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                .DirectoryInvocationReadMapper.class)
        .completeDirectoryInvocation(ledger.queryContext(), output, digest, tenant, invocation);
  }

  public int completeDirectoryBinding(
      long revision,
      boolean changed,
      String attempt,
      long fence,
      Long seq,
      String proof,
      String tenant,
      String invocation) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                .DirectoryPathReadMapper.class)
        .completeDirectoryBinding(
            ledger.queryContext(),
            revision,
            changed,
            attempt,
            fence,
            seq,
            proof,
            tenant,
            invocation);
  }

  public int advanceDirectoryRevision(
      String cwd, long revision, String invocation, String tenant, String task, long base) {
    return ledger
        .mappers
        .session()
        .getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                .TaskDirectoryReadMapper.class)
        .advanceDirectoryRevision(
            ledger.queryContext(), cwd, revision, invocation, tenant, task, base);
  }

  public Row directoryFileInvocation(String tenant, String task, String invocation) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .DirectoryInvocationReadMapper.class)
                .directoryFileInvocation(ledger.queryContext(), tenant, task, invocation)));
  }

  public Row directoryOwnerView(String tenant, String task, String actor) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper
                        .TaskDirectoryReadMapper.class)
                .directoryOwnerView(ledger.queryContext(), tenant, task, actor)));
  }

  public List<Row> directoryViewEvent(String tenant, String task, long seq, String fact) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper.EventMapper.class)
            .directoryViewEvent(ledger.queryContext(), tenant, task, seq, fact));
  }

  public List<Row> directoryViewAttempt(String tenant, String task, String attempt, long fence) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptMapper
                    .class)
            .directoryViewAttempt(ledger.queryContext(), tenant, task, attempt, fence));
  }
}
