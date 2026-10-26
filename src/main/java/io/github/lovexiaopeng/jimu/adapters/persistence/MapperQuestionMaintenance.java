package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionMaintenanceMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMaintenance;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Construction stores references only; private single-operation helpers remain in Ledger. */
final class MapperQuestionMaintenance implements QuestionMaintenance {
  private final JdbcLedger ledger;
  private final Function<Supplier<Void>, Void> transaction;
  private final BiConsumer<Map<String, Object>, String> close;
  private final BiConsumer<Map<String, Object>, Map<String, Object>> continuation;

  MapperQuestionMaintenance(
      JdbcLedger ledger,
      Function<Supplier<Void>, Void> transaction,
      BiConsumer<Map<String, Object>, String> close,
      BiConsumer<Map<String, Object>, Map<String, Object>> continuation) {
    this.ledger = ledger;
    this.transaction = transaction;
    this.close = close;
    this.continuation = continuation;
  }

  public boolean enabled() {
    return ledger.questionsEnabled;
  }

  public List<Map<String, Object>> candidates(boolean enabled) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionMaintenanceMapper.class)
        .candidates(ledger.queryContext(), enabled);
  }

  public Void transaction(Supplier<Void> work) {
    return transaction.apply(work);
  }

  public void bucket(String tenant) {
    ledger.bucket(tenant);
  }

  public Map<String, Object> locked(String tenant, String task) {
    return ledger.locked(tenant, task);
  }

  public void access(Map<String, Object> task) {
    ledger.access(task);
  }

  public void close(Map<String, Object> task, String code) {
    close.accept(task, code);
  }

  public void terminal(Map<String, Object> task, String state, String code) {
    ledger.terminal(task, state, code);
  }

  public List<Map<String, Object>> due(String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(QuestionMaintenanceMapper.class)
        .due(ledger.queryContext(), tenant, task);
  }

  public void continueQuestion(Map<String, Object> task, Map<String, Object> question) {
    continuation.accept(task, question);
  }

  public void settle(Map<String, Object> task) {
    ledger.settleStoppedWriters(task);
  }
}
