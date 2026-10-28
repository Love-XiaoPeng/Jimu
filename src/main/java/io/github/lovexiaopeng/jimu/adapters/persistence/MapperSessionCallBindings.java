package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionReadBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionScopeBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionSearchBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionTraceBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryCutReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionReadBindingMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeBindingMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionSearchBindingMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionTraceBindingMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTraceContracts;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionScopeOperation;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallBindings;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventTracePort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.util.Iterator;
import java.util.Map;

/** Actual MP rows/inserts on original DS/UoW and explicit query; codecs/NULL access remain lazy. */
final class MapperSessionCallBindings implements SessionCallBindings {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final String stepId;
  private Map<String, Object> history, step;
  // 此对象仅归属一次bind调用；调用者已按排序锁住caller及全部来源直到M2提交。
  // 复用同一批次的逐节点核权和不可变图证明，不跨事务/响应/原槽执行缓存。
  private JdbcSessionScopes.Scope batchScope;
  private Map<String, Object> batchCut;
  private io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored batchGraph;

  MapperSessionCallBindings(JdbcSessionRecords records, Map<String, Object> task, String stepId) {
    this.records = records;
    this.task = task;
    this.stepId = stepId;
  }

  public void lockHistory() {
    if (lineageTask()
        && !org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive())
      throw new IllegalStateException("Lineage response binding requires its owning transaction");
    history = records.history(task, true);
  }

  public void loadStep() {
    step =
        bindingRow(
            ScopeQueryStepReadMapper.class,
            new QueryWrapper<>()
                .select(Schema22Columns.STEP)
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("id", stepId)
                .eq("task_id", s(task, "id"))
                .apply(
                    "(NOT {0} OR (OCTET_LENGTH(request_snapshot)<=131072 AND COALESCE(OCTET_LENGTH(response_snapshot),0)<=16384))",
                    records.priorProof()));
  }

  private Map<String, Object> bindingRow(Class<?> mapper, Object wrapper) {
    var rows = records.bindingRows(mapper, wrapper);
    if (rows.size() != 1) throw JdbcSessionRecords.corrupt();
    return rows.getFirst();
  }

  public boolean hasUpper() {
    return step.get("session_upper_seq") != null;
  }

  public String historyBinding() {
    return s(history, "binding_digest");
  }

  public String stepBinding() {
    return s(step, "session_binding_digest");
  }

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public boolean lineageTask() {
    return JdbcSessionScopes.lineage(task);
  }

  public boolean traceTask() {
    return JdbcSessionRecords.traceTask(task);
  }

  public boolean searchTask() {
    return JdbcSessionRecords.searchTask(task);
  }

  public Iterable<SessionCallBindings.Invocation> invocations() {
    var rows =
        records.bindingRows(
            HistoryInvocationReadMapper.class,
            new QueryWrapper<>()
                .select(Schema22Columns.INVOCATION)
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("task_id", s(task, "id"))
                .eq("step_id", stepId)
                .apply(
                    "(NOT {0} OR (OCTET_LENGTH(input_snapshot)<=4096 AND COALESCE(OCTET_LENGTH(output_snapshot),0)<=32768))",
                    records.priorProof())
                .orderByAsc("ordinal"));
    return () ->
        new Iterator<>() {
          final Iterator<Map<String, Object>> source = rows.iterator();

          public boolean hasNext() {
            return source.hasNext();
          }

          public SessionCallBindings.Invocation next() {
            return new Entry(source.next());
          }
        };
  }

  private record PendingRead(
      String tenant,
      String invocation,
      String task,
      String step,
      String binding,
      String input,
      long upper,
      long target,
      long before,
      long after) {
    SessionReadBindingEntity row(int relation) {
      return new SessionReadBindingEntity(
          tenant,
          invocation,
          task,
          step,
          binding,
          input,
          upper,
          target,
          before,
          after,
          (long) relation);
    }
  }

  private final class Entry implements SessionCallBindings.Invocation {
    private final Map<String, Object> invocation;
    private SessionEventTracePort.Input trace;
    private SessionEventPort.Input read;
    private PendingRead pending;
    private String normalized;
    private JdbcSessionScopes.Scope scope;
    private Map<String, Object> cut;

    Entry(Map<String, Object> invocation) {
      this.invocation = invocation;
    }

    public String tool() {
      return s(invocation, "tool");
    }

    public void normalizeLineage() {
      String requested = records.lineage.input(s(invocation, "input_snapshot"));
      String target = requested == null ? s(task, "id") : requested;
      if (!target.equals(s(task, "id"))
          && scope.sources().stream().noneMatch(t -> target.equals(s(t, "id"))))
        throw JdbcSessionScopes.unavailable();
      normalized = Payloads.json(Map.of("version", 1, "target_session_id", target));
    }

    public void insertLineage() {
      if (batchGraph == null) {
        io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryDiagnostics.measure(
            records.appendQuery(),
            "M2.binding.graph",
            () -> {
              records.lineageService.verify(s(task, "tenant_id"), s(task, "id"));
              return null;
            });
        batchGraph = records.lineageRecords.load(s(task, "tenant_id"), s(task, "id"));
      }
      var graph = batchGraph;
      var context = records.v5.json(s(cut, "context_snapshot"), 4096);
      if (!graph.graphDigest().equals(context.path("graph_sha256").asText())
          || !graph.genesisDigest().equals(context.path("genesis_sha256").asText()))
        throw JdbcSessionRecords.corrupt();
      records.insertBinding(
          io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionLineageTraceMapper
              .class,
          new io.github.lovexiaopeng.jimu.adapters.persistence.session.entity
              .SessionLineageTraceEntity(
              s(task, "tenant_id"),
              s(invocation, "id"),
              s(task, "id"),
              stepId,
              s(history, "binding_digest"),
              s(invocation, "input_digest"),
              s(scope.row(), "authority_digest"),
              graph.graphDigest(),
              s(cut, "context_digest"),
              normalized,
              Payloads.hash(normalized),
              (long) SessionContracts.bytes(normalized)));
    }

    public void parseTrace() {
      trace = records.trace.input(s(invocation, "input_snapshot"));
    }

    public void insertTrace() {
      records.insertBinding(
          SessionTraceBindingMapper.class,
          new SessionTraceBindingEntity(
              s(task, "tenant_id"),
              s(invocation, "id"),
              s(task, "id"),
              stepId,
              s(history, "binding_digest"),
              s(invocation, "input_digest"),
              n(step, "session_upper_seq"),
              trace.seq(),
              SessionTraceContracts.POLICY));
    }

    public void normalizeSearch() {
      var input = records.searchContract(task).input(s(invocation, "input_snapshot"));
      normalized =
          records
              .searchContract(task)
              .normalized(input, s(task, "id"), n(step, "session_upper_seq"));
    }

    public void chargeSearch() {
      records.body(task, SessionContracts.bytes(normalized));
    }

    public void insertSearch() {
      records.insertBinding(
          SessionSearchBindingMapper.class,
          new SessionSearchBindingEntity(
              s(task, "tenant_id"),
              s(invocation, "id"),
              s(task, "id"),
              stepId,
              s(history, "binding_digest"),
              s(invocation, "input_digest"),
              n(step, "session_upper_seq"),
              normalized,
              Payloads.hash(normalized),
              (long) SessionContracts.bytes(normalized)));
    }

    public void parseRead() {
      read = records.contracts.input(s(invocation, "input_snapshot"));
    }

    public void prepareRead() {
      pending =
          new PendingRead(
              s(task, "tenant_id"),
              s(invocation, "id"),
              s(task, "id"),
              stepId,
              s(history, "binding_digest"),
              s(invocation, "input_digest"),
              n(step, "session_upper_seq"),
              read.seq(),
              read.before(),
              read.after());
    }

    public void insertRead(int relationVersion) {
      records.insertBinding(SessionReadBindingMapper.class, pending.row(relationVersion));
    }

    public void requireScope() {
      if (!lineageTask()) {
        scope = records.scopes.require(task);
        return;
      }
      records.appendQuery().remainingMillis();
      if (batchScope == null) batchScope = records.scopes.require(task);
      scope = batchScope;
    }

    public void normalizeScope() {
      normalized =
          records.normalizedScope(
              task, step, s(invocation, "tool"), s(invocation, "input_snapshot"));
    }

    public void loadCut() {
      if (lineageTask() && batchCut != null) {
        cut = batchCut;
        return;
      }
      cut =
          bindingRow(
              ScopeQueryCutReadMapper.class,
              new QueryWrapper<>()
                  .select(Schema22Columns.STEP_SESSION_SCOPE)
                  .eq("tenant_id", s(task, "tenant_id"))
                  .eq("step_id", s(step, "id"))
                  .eq("caller_task_id", s(task, "id")));
      if (lineageTask()) batchCut = cut;
    }

    public String authority() {
      return s(scope.row(), "authority_digest");
    }

    public String cutAuthority() {
      return s(cut, "authority_digest");
    }

    public String stepContext() {
      return s(step, "session_context_digest");
    }

    public String cutContext() {
      return s(cut, "context_digest");
    }

    public void insertScope() {
      records.insertBinding(
          SessionScopeBindingMapper.class,
          new SessionScopeBindingEntity(
              s(task, "tenant_id"),
              s(invocation, "id"),
              s(task, "id"),
              s(step, "id"),
              s(invocation, "tool").equals(ToolCapability.SESSION_SEARCH)
                  ? SessionScopeOperation.SEARCH
                  : SessionScopeOperation.READ,
              s(step, "session_binding_digest"),
              s(invocation, "input_digest"),
              s(scope.row(), "authority_digest"),
              s(cut, "context_digest"),
              normalized,
              Payloads.hash(normalized),
              (long) SessionContracts.bytes(normalized)));
    }

    public void chargeScope() {
      records.body(task, SessionContracts.bytes(normalized));
    }
  }
}
