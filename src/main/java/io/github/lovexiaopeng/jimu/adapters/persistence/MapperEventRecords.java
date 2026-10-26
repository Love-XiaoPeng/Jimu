package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.event.port.EventRecords;
import java.util.Map;

/** 不加事务、不预取；沿调用者task锁和query执行原Mapper变更。 */
final class MapperEventRecords implements EventRecords {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperEventRecords(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  private String tenant() {
    return ledger.s(task, "tenant_id");
  }

  private String id() {
    return ledger.s(task, "id");
  }

  public boolean exists(String fact) {
    return !ledger
        .mappers
        .events()
        .selectFactSequence(ledger.queryContext(), tenant(), id(), fact)
        .isEmpty();
  }

  public boolean todo() {
    return ledger.todoTask(task);
  }

  public boolean directory() {
    return ledger.directoryTask(task);
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public long sequence() {
    return ledger.mappers.tasks().selectNextEventSequence(ledger.queryContext(), tenant(), id());
  }

  public void advance() {
    ledger.mappers.tasks().advanceEventSequence(ledger.queryContext(), tenant(), id());
  }

  public void insert(long seq, String fact, String type, String code, String artifact) {
    ledger
        .mappers
        .events()
        .insertFact(
            ledger.queryContext(),
            tenant(),
            id(),
            seq,
            id() + ":" + seq,
            fact,
            type,
            code,
            ledger.mappers.tasks().selectState(ledger.queryContext(), tenant(), id()),
            artifact);
  }

  public void sessionFact(String fact) {
    ledger.sessionFact(task, fact);
  }

  public void sessionAccount(boolean closing) {
    ledger.sessions.account(task, closing);
  }

  public void directoryAccount(boolean closing) {
    ledger.directoryMetadata(task, closing);
  }

  public void todoAccount(boolean closing) {
    ledger.todoMetadata(task, closing);
  }
}
