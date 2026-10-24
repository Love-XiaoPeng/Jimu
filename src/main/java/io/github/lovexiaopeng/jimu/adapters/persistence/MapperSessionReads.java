package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.NativeReadReceiptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryNativeEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryStepEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.NativeReadReceiptMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeQueryMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 同DS显式query和原事务边界；普通投影用MP，锁和条件写用参数化Mapper。 */
final class MapperSessionReads implements SessionReads {
  private final JdbcLedger ledger;
  private final SessionReadMapper sql;
  private final SessionScopeQueryMapper settlement;

  MapperSessionReads(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionReadMapper.class);
    settlement = ledger.mappers.session().getMapper(SessionScopeQueryMapper.class);
  }

  public <T> T required(Supplier<T> work) {
    try {
      return ledger.sessionTransaction(work, System.nanoTime() + 2_000_000_000L);
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

  private Map<String, Object> first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return rows.getFirst();
  }

  private Map<String, Object> read(Class<?> mapper, Object wrapper) {
    var args = new HashMap<String, Object>();
    args.put("query", ledger.queryContext());
    args.put("ew", wrapper);
    return first(ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", args));
  }

  private final class Record implements Task {
    private final Lease lease;
    private final Map<String, Object> task;

    Record(Lease lease, Map<String, Object> task) {
      this.lease = lease;
      this.task = task;
    }

    public boolean enabled() {
      return JdbcSessionRecords.task(task);
    }

    public boolean trace() {
      return JdbcSessionRecords.traceTask(task);
    }

    public Invocation originalSlot(String step, Call call, String version, String digest) {
      var i = ledger.originalDirectorySlot(lease, step, call, version, digest);
      return new Invocation(
          s(i, "id"), s(i, "state"), s(i, "input_digest"), s(i, "output_snapshot"));
    }

    public Input input(String raw) {
      var i = ledger.sessions.contracts.input(raw);
      return new Input(i.seq(), i.sessionId(), i.before(), i.after());
    }

    public String historyBinding() {
      return s(ledger.sessions.history(task, true), "binding_digest");
    }

    public Step validatedStep(String step) {
      var r =
          MapperSessionReads.this.read(
              ScopeQueryStepReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryStepEntity>()
                  .eq(ScopeQueryStepEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryStepEntity::getRowId, step)
                  .eq(ScopeQueryStepEntity::getTaskId, lease.taskId()));
      ledger.sessions.validateStep(task, r);
      return new Step(s(r, "session_binding_digest"), n(r, "session_upper_seq"));
    }

    public Binding lockBinding(String invocation) {
      var r = first(sql.lockBinding(ledger.queryContext(), lease.tenant(), invocation));
      return new Binding(
          n(r, "record_version"),
          s(r, "task_id"),
          s(r, "step_id"),
          s(r, "binding_digest"),
          n(r, "upper_seq"),
          s(r, "input_digest"),
          n(r, "target_seq"),
          n(r, "before_count"),
          n(r, "after_count"),
          trace() ? n(r, "relation_version") : 0,
          s(r, "outcome"),
          s(r, "failure_code"),
          r.get("receipt_seq") == null ? null : Long.valueOf(n(r, "receipt_seq")));
    }

    public void verifyReceipt(long seq) {
      var r =
          MapperSessionReads.this.read(
              ScopeQueryNativeReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryNativeEntity>()
                  .eq(ScopeQueryNativeEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryNativeEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryNativeEntity::getSeq, seq));
      ledger.sessions.verify(task, r);
    }

    public void validateOutput(String output) {
      ledger.sessions.contract(task).output(output);
    }

    public long toolsUsed() {
      return n(task, "tools_used");
    }

    public long maxTools() {
      return n(task, "max_tools");
    }

    public Budget budget() {
      var b = ledger.bucket(lease.tenant());
      return new Budget(n(b, "tool_used"), n(b, "tool_limit"));
    }

    public Read read(Input input, long upper) {
      var r =
          ledger.sessions.read(
              task,
              new SessionEventPort.Input(
                  input.seq(), input.sessionId(), input.before(), input.after()),
              upper,
              "pre-step",
              () -> ledger.queryContext().remainingMillis());
      return new Read(r.output(), r.from(), r.to(), r.targetDigest(), r.sources());
    }

    public void body(long bytes) {
      ledger.sessions.body(task, bytes);
    }

    public void consumeTask() {
      settlement.consumeTask(ledger.queryContext(), lease.tenant(), lease.taskId());
    }

    public void consumeTenant() {
      settlement.consumeTenant(ledger.queryContext(), lease.tenant());
    }

    public void reserve(String invocation) {
      ledger.reserve(
          lease.tenant(), lease.taskId(), invocation, invocation, "TOOL", 1, "SETTLED", "KNOWN");
    }

    public void settle(String invocation) {
      settlement.settle(ledger.queryContext(), lease.tenant(), invocation);
    }

    public void finishInvocation(String invocation, String outcome, String output, String digest) {
      settlement.finishInvocation(
          ledger.queryContext(), outcome, output, digest, lease.tenant(), invocation);
    }

    public long nextSequence() {
      return n(ledger.sessions.history(task, true), "next_seq");
    }

    public void commitRead(String invocation, Commit commit) {
      sql.commitRead(
          ledger.queryContext(),
          commit,
          lease.attemptId(),
          lease.fence(),
          lease.tenant(),
          invocation);
    }

    public void event(String fact, String type, String code) {
      ledger.event(task, fact, type, code);
    }

    public void appendRead(String step, Invocation invocation) {
      var r =
          MapperSessionReads.this.read(
              NativeReadReceiptMapper.class,
              new LambdaQueryWrapper<NativeReadReceiptEntity>()
                  .eq(NativeReadReceiptEntity::getTenantId, lease.tenant())
                  .eq(NativeReadReceiptEntity::getInvocationId, invocation.id()));
      ledger.sessionAppend(
          task,
          "read:" + invocation.id(),
          "session.read",
          ledger.sessions.source("invocation", step, invocation.id()),
          ledger.sessions.receipt(r),
          false,
          invocation.inputDigest(),
          lease);
    }

    public void commitFence() {
      ledger.todoCommitFence(lease);
    }

    public void account() {
      ledger.sessions.account(task, false);
    }
  }
}
