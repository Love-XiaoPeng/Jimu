package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryAttemptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryReceiptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryReservationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryStepEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryAttemptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReceiptReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReservationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeQueryMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionSearchMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionSearches;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventSearchPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 同DS显式query和原事务边界；普通投影用MP，锁和条件写用参数化Mapper。 */
final class MapperSessionSearches implements SessionSearches {
  private final JdbcLedger ledger;
  private final SessionSearchMapper sql;
  private final SessionScopeQueryMapper settlement;

  MapperSessionSearches(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionSearchMapper.class);
    settlement = ledger.mappers.session().getMapper(SessionScopeQueryMapper.class);
  }

  public <T> T required(Supplier<T> work) {
    try {
      return ledger.sessionTransaction(work, System.nanoTime() + 2_000_000_000L);
    } catch (
        io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionTransactionUnconfirmed
            failure) {
      throw new SessionCommitUnconfirmed(failure);
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

  private static Long nullable(Map<String, Object> r, String key) {
    return r.get(key) == null ? null : Long.valueOf(n(r, key));
  }

  private Map<String, Object> first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
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
      return JdbcSessionRecords.searchTask(task);
    }

    public Invocation originalSlot(String step, Call call, String version, String digest) {
      var i = ledger.originalDirectorySlot(lease, step, call, version, digest);
      return new Invocation(
          s(i, "id"), s(i, "state"), s(i, "output_snapshot"), s(i, "output_digest"));
    }

    public Input input(String raw) {
      var i = ledger.sessions.searchContract(task).input(raw);
      return new Input(
          i.query(),
          i.sessionId(),
          i.seqFrom(),
          i.seqTo(),
          i.timeFrom(),
          i.timeTo(),
          i.eventTypes());
    }

    private SessionEventSearchPort.Input wire(Input i) {
      return new SessionEventSearchPort.Input(
          i.query(), i.session(), i.from(), i.to(), i.timeFrom(), i.timeTo(), i.types());
    }

    public String normalized(Input input, long upper) {
      return ledger.sessions.searchContract(task).normalized(wire(input), lease.taskId(), upper);
    }

    public String historyBinding() {
      return s(ledger.sessions.history(task, true), "binding_digest");
    }

    public Step validatedStep(String step) {
      var r =
          MapperSessionSearches.this.read(
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
          s(r, "content_state"),
          s(r, "normalized_query_snapshot"),
          s(r, "normalized_query_digest"),
          n(r, "normalized_query_bytes"),
          s(r, "outcome"),
          s(r, "failure_code"),
          s(r, "output_digest"),
          nullable(r, "output_bytes"),
          nullable(r, "receipt_event_seq"),
          s(r, "committed_attempt_id"),
          nullable(r, "committed_fence"));
    }

    public Receipt receipt(long seq) {
      var r =
          MapperSessionSearches.this.read(
              ScopeQueryReceiptReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryReceiptEntity>()
                  .eq(ScopeQueryReceiptEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryReceiptEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryReceiptEntity::getSeq, seq));
      return new Receipt(s(r, "fact_id"), s(r, "type"), s(r, "code"));
    }

    public Reservation reservation(String invocation) {
      var r =
          MapperSessionSearches.this.read(
              ScopeQueryReservationReadMapper.class,
              new LambdaQueryWrapper<ScopeQueryReservationEntity>()
                  .eq(ScopeQueryReservationEntity::getTenantId, lease.tenant())
                  .eq(ScopeQueryReservationEntity::getTaskId, lease.taskId())
                  .eq(ScopeQueryReservationEntity::getRowId, invocation));
      return new Reservation(s(r, "category"), s(r, "state"), n(r, "actual"));
    }

    public void requireAttempt(String attempt, long fence) {
      MapperSessionSearches.this.read(
          ScopeQueryAttemptReadMapper.class,
          new LambdaQueryWrapper<ScopeQueryAttemptEntity>()
              .select(ScopeQueryAttemptEntity::getRowId)
              .eq(ScopeQueryAttemptEntity::getTenantId, lease.tenant())
              .eq(ScopeQueryAttemptEntity::getTaskId, lease.taskId())
              .eq(ScopeQueryAttemptEntity::getRowId, attempt)
              .eq(ScopeQueryAttemptEntity::getFence, fence));
    }

    public void validateOutput(String output) {
      ledger.sessions.searchContract(task).output(output);
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

    public Search search(Input input, long upper) {
      var r =
          ledger.sessions.search(
              task, wire(input), upper, "pre-step", () -> ledger.queryContext().remainingMillis());
      return new Search(r.output(), r.scanDigest(), r.rows(), r.eventBytes());
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
      settlement.settle(ledger.queryContext(), lease.tenant(), invocation);
    }

    public void finishInvocation(String invocation, String outcome, String output, String digest) {
      settlement.finishInvocation(
          ledger.queryContext(), outcome, output, digest, lease.tenant(), invocation);
    }

    public long receiptSequence(String fact) {
      return n(
          first(
              ledger
                  .mappers
                  .events()
                  .selectFactSequence(ledger.queryContext(), lease.tenant(), lease.taskId(), fact)),
          "seq");
    }

    public void commitSearch(String invocation, Commit commit) {
      sql.commitSearch(
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

    public void commitFence() {
      ledger.todoCommitFence(lease);
    }

    public void deadline() {
      ledger.queryContext().remainingMillis();
    }

    public void account() {
      ledger.sessions.account(task, false);
    }
  }
}
