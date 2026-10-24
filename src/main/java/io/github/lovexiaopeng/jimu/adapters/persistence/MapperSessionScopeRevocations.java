package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeRevocationMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRevocations;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;
import java.util.function.Supplier;

/** 同DS／显式query的锁与CAS，取消和计账复用既有唯一Service链。 */
final class MapperSessionScopeRevocations implements SessionScopeRevocations {
  private final JdbcLedger ledger;
  private final SessionScopeRevocationMapper sql;

  MapperSessionScopeRevocations(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionScopeRevocationMapper.class);
  }

  public <T> T required(long end, Supplier<T> work) {
    return ledger.sessionSearchOwnerRead(work, end);
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public void lockScope(String tenant, String caller) {
    ledger.sessionScopes.lock(tenant, caller);
  }

  public void requireIdentity(Identity identity) {
    ledger.identity(identity);
  }

  public List<Owner> owners(Identity identity, String caller) {
    return sql
        .lockOwner(ledger.queryContext(), identity.tenant(), caller, identity.actor())
        .stream()
        .<Owner>map(
            row ->
                new Owner() {
                  public boolean scopeTask() {
                    return JdbcSessionScopes.task(row);
                  }

                  public String state() {
                    return JdbcLedger.s(row, "state");
                  }

                  public void invalidateAfterTransaction() {
                    ledger.invalidateScopeAfterTransaction(row);
                  }

                  public void revoke() {
                    sql.revoke(ledger.queryContext(), identity.tenant(), caller);
                  }

                  public void cancel(Identity actor) {
                    ledger.cancel(actor, caller);
                  }

                  public void account() {
                    ledger.sessions.account(row, true);
                  }
                })
        .toList();
  }
}
