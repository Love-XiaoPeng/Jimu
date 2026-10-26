package io.github.lovexiaopeng.jimu.kernel.invocation.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.function.Supplier;

/** Lazy original rows and individual persistence/proof operations for Web observations. */
public interface WebFetchRecords {
  interface Task {
    void event(String fact, String type, String code);
  }

  interface Invocation {
    Object tool();

    Object version();

    Object state();

    Object stepId();

    Object output();

    Object outputDigest();
  }

  interface Step {}

  interface Receipt {
    Object code();
  }

  <T> T transaction(Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  Task lockDiagnosticTask(Lease lease);

  int diagnosticBindings(Lease lease, String invocation);

  boolean cancellationRecorded(Lease lease, String fact);

  Invocation invocation(Lease lease, String invocation);

  Step step(Lease lease, Object stepId);

  String proof(Task task, Step step, Invocation invocation, String output);

  List<Receipt> receipts(Lease lease, String fact);

  String hash(String output);

  void preserve(Lease lease, String invocation, String output, String digest);
}
