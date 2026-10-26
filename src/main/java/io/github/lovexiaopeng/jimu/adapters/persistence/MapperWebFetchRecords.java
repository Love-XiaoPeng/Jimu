package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.WebFetchRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Same DS/UoW; wrappers retain raw Map references and defer each member read. */
final class MapperWebFetchRecords implements WebFetchRecords {
  private final JdbcLedger ledger;

  MapperWebFetchRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public <T> T transaction(Supplier<T> work) {
    return ledger.tx(work);
  }

  private record TaskRow(JdbcLedger ledger, Map<String, Object> row) implements Task {
    public void event(String fact, String type, String code) {
      ledger.event(row, fact, type, code);
    }
  }

  private record InvocationRow(Map<String, Object> row) implements Invocation {
    public Object tool() {
      return row.get("tool");
    }

    public Object version() {
      return row.get("plugin_version");
    }

    public Object state() {
      return row.get("state");
    }

    public Object stepId() {
      return row.get("step_id");
    }

    public Object output() {
      return row.get("output_snapshot");
    }

    public Object outputDigest() {
      return row.get("output_digest");
    }
  }

  private record StepRow(Map<String, Object> row) implements Step {}

  public Task fenced(Lease lease, boolean admission) {
    return new TaskRow(ledger, ledger.fenced(lease, admission));
  }

  public Task lockDiagnosticTask(Lease lease) {
    return new TaskRow(
        ledger,
        JdbcLedger.first(
            ledger
                .mappers
                .tasks()
                .selectWebDiagnosticTask(ledger.queryContext(), lease.tenant(), lease.taskId())));
  }

  public int diagnosticBindings(Lease lease, String invocation) {
    return ledger
        .mappers
        .invocations()
        .selectWebDiagnosticBindings(
            ledger.queryContext(),
            lease.tenant(),
            lease.taskId(),
            invocation,
            lease.attemptId(),
            lease.worker(),
            lease.fence())
        .size();
  }

  public boolean cancellationRecorded(Lease lease, String fact) {
    // queryContext returns precisely the original query field; no lease-derived context.
    return !ledger
        .mappers
        .events()
        .selectFactSequence(ledger.queryContext(), lease.tenant(), lease.taskId(), fact)
        .isEmpty();
  }

  public Invocation invocation(Lease lease, String invocation) {
    return new InvocationRow(
        JdbcLedger.first(
            ledger
                .mappers
                .invocations()
                .selectTaskInvocation(
                    ledger.queryContext(), lease.tenant(), lease.taskId(), invocation)));
  }

  public Step step(Lease lease, Object stepId) {
    return new StepRow(
        JdbcLedger.first(
            ledger
                .mappers
                .steps()
                .selectWebReceiptStep(
                    ledger.queryContext(), lease.tenant(), lease.taskId(), stepId)));
  }

  public String proof(Task task, Step step, Invocation invocation, String output) {
    return WebFetchReceipts.digest(
        ((TaskRow) task).row(), ((StepRow) step).row(), ((InvocationRow) invocation).row(), output);
  }

  public List<Receipt> receipts(Lease lease, String fact) {
    return ledger
        .mappers
        .events()
        .selectFactCode(ledger.queryContext(), lease.tenant(), lease.taskId(), fact)
        .stream()
        .<Receipt>map(row -> () -> row.get("code"))
        .toList();
  }

  public String hash(String output) {
    return Payloads.hash(output);
  }

  public void preserve(Lease lease, String invocation, String output, String digest) {
    ledger
        .mappers
        .invocations()
        .preserveWebResult(
            ledger.queryContext(), output, digest, lease.tenant(), lease.taskId(), invocation);
  }
}
