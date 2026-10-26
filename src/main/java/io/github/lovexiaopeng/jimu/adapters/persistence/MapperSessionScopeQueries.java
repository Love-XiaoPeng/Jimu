package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryAttemptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryBindingEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryCutEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryNativeEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryReceiptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryReservationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryStepEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryAttemptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryBindingReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryCutReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReceiptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReservationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeQueryMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 显式query／同DS事务内的MP读取、锁／CAS及原共享协议转换，不承载查询结算分支。 */
final class MapperSessionScopeQueries implements SessionScopeQueries {
  private final JdbcLedger ledger;
  private final SessionScopeQueryMapper sql;

  MapperSessionScopeQueries(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionScopeQueryMapper.class);
  }

  public <T> T required(long end, Supplier<T> work) {
    try {
      return ledger.sessionTransaction(work, end);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException failure) {
      throw new SessionCommitUnconfirmed();
    }
  }

  public Task lock(Lease lease) {
    return new Record(lease, ledger.fenced(lease, true));
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public int bytes(String raw) {
    return SessionContracts.bytes(raw);
  }

  public boolean sameJson(String left, String right) {
    return ledger
        .sessions
        .contracts
        .json(left, 4096)
        .equals(ledger.sessions.contracts.json(right, 4096));
  }

  private static Long nullable(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((Number) row.get(key)).longValue();
  }

  private Map<String, Object> firstRecord(List<Map<String, Object>> rows) {
    if (rows.isEmpty())
      throw new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
          404, CommonErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  private Map<String, Object> read(Class<?> mapper, Object wrapper) {
    var args = new HashMap<String, Object>();
    args.put("query", ledger.queryContext());
    args.put("ew", wrapper);
    return firstRecord(ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", args));
  }

  private static Binding binding(Map<String, Object> r) {
    return new Binding(
        n(r, "record_version"),
        s(r, "caller_task_id"),
        s(r, "step_id"),
        s(r, "operation"),
        s(r, "binding_digest"),
        s(r, "input_digest"),
        s(r, "authority_digest"),
        s(r, "context_digest"),
        s(r, "normalized_snapshot"),
        s(r, "normalized_digest"),
        n(r, "normalized_bytes"),
        s(r, "content_state"),
        s(r, "outcome"),
        s(r, "failure_code"),
        s(r, "output_digest"),
        nullable(r, "output_bytes"),
        nullable(r, "receipt_event_seq"),
        nullable(r, "native_read_seq"),
        s(r, "committed_attempt_id"),
        nullable(r, "committed_fence"));
  }

  private final class Record implements Task {
    private final Lease lease;
    private final Map<String, Object> task;
    private Map<String, Object> step;

    Record(Lease lease, Map<String, Object> task) {
      this.lease = lease;
      this.task = task;
    }

    public boolean scopeTask() {
      return JdbcSessionScopes.task(task);
    }

    public long toolsUsed() {
      return n(task, "tools_used");
    }

    public long maxTools() {
      return n(task, "max_tools");
    }

    public Invocation originalSlot(String stepId, Call call, String version, String digest) {
      var i = ledger.originalDirectorySlot(lease, stepId, call, version, digest);
      return new Invocation(
          s(i, "id"),
          s(i, "state"),
          s(i, "input_digest"),
          s(i, "output_snapshot"),
          s(i, "output_digest"));
    }

    public Step validatedStep(String stepId) {
      step =
          MapperSessionScopeQueries.this.read(
              ScopeQueryStepReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryStepEntity>()
                  .eq(ScopeQueryStepEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryStepEntity::getRowId, stepId)
                  .eq(ScopeQueryStepEntity::getTaskId, lease.taskId()));
      ledger.sessions.validateStep(task, step);
      return new Step(s(step, "session_binding_digest"), s(step, "session_context_digest"));
    }

    public String requireScope() {
      return s(ledger.sessionScopes.require(task).row(), "authority_digest");
    }

    public Cut cut(String stepId) {
      var r =
          MapperSessionScopeQueries.this.read(
              ScopeQueryCutReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryCutEntity>()
                  .eq(ScopeQueryCutEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryCutEntity::getStepId, stepId)
                  .eq(ScopeQueryCutEntity::getCallerTaskId, lease.taskId()));
      return new Cut(s(r, "authority_digest"), s(r, "context_digest"), s(r, "context_snapshot"));
    }

    public Binding lockBinding(String invocation) {
      return binding(
          firstRecord(sql.lockBinding(ledger.queryContext(), lease.tenant(), invocation)));
    }

    public String normalized(Call call) {
      return ledger.sessions.normalizedScope(task, step, call.tool(), call.input());
    }

    public Receipt receipt(long seq) {
      var r =
          MapperSessionScopeQueries.this.read(
              ScopeQueryReceiptReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryReceiptEntity>()
                  .eq(ScopeQueryReceiptEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryReceiptEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryReceiptEntity::getSeq, seq));
      return new Receipt(s(r, "fact_id"), s(r, "type"), s(r, "code"));
    }

    public Reservation reservation(String invocation) {
      var r =
          MapperSessionScopeQueries.this.read(
              ScopeQueryReservationReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryReservationEntity>()
                  .eq(ScopeQueryReservationEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryReservationEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryReservationEntity::getRowId, invocation));
      return new Reservation(s(r, "category"), s(r, "state"), n(r, "actual"));
    }

    public void requireAttempt(String attempt, long fence) {
      MapperSessionScopeQueries.this.read(
          ScopeQueryAttemptReadMapper.class,
          new LambdaQueryWrapper<ScopeQueryAttemptEntity>()
              .select(ScopeQueryAttemptEntity::getRowId)
              .eq(ScopeQueryAttemptEntity::getTenantId, lease.tenant())
              .eq(ScopeQueryAttemptEntity::getTaskId, lease.taskId())
              .eq(ScopeQueryAttemptEntity::getRowId, attempt)
              .eq(ScopeQueryAttemptEntity::getFence, fence));
    }

    public void validateReadOutput(String output) {
      ledger.sessions.v4.output(output);
    }

    public void validateSearchOutput(String output) {
      ledger.sessionScopes.contracts.output(output);
    }

    public void verifyNative(long seq) {
      var r =
          MapperSessionScopeQueries.this.read(
              ScopeQueryNativeReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryNativeEntity>()
                  .eq(ScopeQueryNativeEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryNativeEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryNativeEntity::getSeq, seq));
      ledger.sessions.verify(task, r);
    }

    public Budget budget() {
      var r = ledger.bucket(lease.tenant());
      return new Budget(n(r, "tool_used"), n(r, "tool_limit"));
    }

    public Result read(String normalized) {
      var r =
          ledger.sessions.scopeRead(
              task, normalized, () -> ledger.queryContext().remainingMillis());
      return new Result(r.output(), r.sources(), null, 0, r.eventBytes());
    }

    public Result search(String raw) {
      var r =
          ledger.sessions.priorSearch(
              task,
              ledger.sessionScopes.contracts.input(raw),
              () -> ledger.queryContext().remainingMillis());
      return new Result(r.output(), null, r.scanDigest(), r.rows(), r.eventBytes());
    }

    public void body(long bytes) {
      ledger.sessions.body(task, bytes);
    }

    public void validateRefs(String refs) {
      ledger.sessionScopes.contracts.refs(refs);
    }

    public int refCount(String refs) {
      return ledger.sessions.contracts.json(refs, 2048).size();
    }

    public void consumeTask() {
      sql.consumeTask(ledger.queryContext(), lease.tenant(), lease.taskId());
    }

    public void consumeTenant() {
      sql.consumeTenant(ledger.queryContext(), lease.tenant());
    }

    public void reserve(String invocation) {
      ledger.reserve(
          lease.tenant(),
          lease.taskId(),
          invocation,
          invocation,
          ReservationCategory.TOOL,
          1,
          ReservationState.SETTLED,
          UsageQuality.KNOWN);
    }

    public void settle(String invocation) {
      sql.settle(ledger.queryContext(), lease.tenant(), invocation);
    }

    public void finishInvocation(String invocation, String outcome, String output, String digest) {
      sql.finishInvocation(
          ledger.queryContext(), outcome, output, digest, lease.tenant(), invocation);
    }

    public void event(String fact, String type, String code) {
      ledger.event(task, fact, type, code);
    }

    public long receiptSequence(String invocation) {
      return n(
          ledger
              .mappers
              .events()
              .selectFactSequence(
                  ledger.queryContext(), lease.tenant(), lease.taskId(), "result:" + invocation)
              .getFirst(),
          "seq");
    }

    public long nextNativeSequence() {
      return n(ledger.sessions.history(task, true), "next_seq");
    }

    public void commitQuery(String invocation, Commit commit) {
      sql.commitQuery(
          ledger.queryContext(),
          commit,
          lease.attemptId(),
          lease.fence(),
          lease.tenant(),
          invocation);
    }

    public void appendRead(String stepId, Invocation invocation) {
      var committed =
          MapperSessionScopeQueries.this.read(
              ScopeQueryBindingReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryBindingEntity>()
                  .eq(ScopeQueryBindingEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryBindingEntity::getInvocationId, invocation.id()));
      ledger.sessionAppend(
          task,
          "read:" + invocation.id(),
          "session.read",
          ledger.sessions.source("invocation", stepId, invocation.id()),
          ledger.sessions.scopeReceipt(committed),
          false,
          invocation.inputDigest(),
          lease);
    }

    public void commitFence() {
      ledger.todoCommitFence(lease);
    }

    public void remaining() {
      ledger.queryContext().remainingMillis();
    }

    public void account() {
      ledger.sessions.account(task, false);
    }
  }
}
