package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.question.port.PendingQuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Stores only the Ledger reference; no session or lease member is read on construction. */
final class LedgerPendingQuestionMessages implements PendingQuestionMessages {
  private final JdbcLedger ledger;

  LedgerPendingQuestionMessages(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public List<String> transaction(Lease lease, Supplier<List<String>> work) {
    return ledger.leaseTx(lease, work);
  }

  public Map<String, Object> fenced(Lease lease, boolean admission) {
    return ledger.fenced(lease, admission);
  }

  public Map<String, String> messages(Map<String, Object> task) {
    return ledger.questionMessages(task);
  }
}
