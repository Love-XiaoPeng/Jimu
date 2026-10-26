package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionOwners;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;
import java.util.Map;

/** Stores references only; no query/context/member access during construction. */
final class MapperSessionOwners implements SessionOwners<Map<String, Object>> {
  private final JdbcLedger ledger;

  MapperSessionOwners(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public void bucket(String tenant) {
    ledger.bucket(tenant);
  }

  public void lockScope(String tenant, String task) {
    ledger.sessionScopes.lock(tenant, task);
  }

  public List<Map<String, Object>> find(String tenant, String task, String actor) {
    return ledger.mappers.tasks().selectSessionOwner(ledger.queryContext(), tenant, task, actor);
  }

  public boolean sessionTask(Map<String, Object> task) {
    return JdbcSessionScopes.task(task);
  }

  public void identity(Identity identity) {
    ledger.identity(identity);
  }

  public void access(Map<String, Object> task) {
    ledger.access(task);
  }
}
