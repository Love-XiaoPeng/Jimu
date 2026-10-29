package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import java.util.Map;

/** Values stay lazy; both mutations use the original DS, query context and caller transaction. */
final class MapperSessionHistoryStorage
    implements io.github.lovexiaopeng.jimu.kernel.session.service.SessionHistoryStorageService
        .Facts {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;

  MapperSessionHistoryStorage(JdbcSessionRecords records, Map<String, Object> task) {
    this.records = records;
    this.task = task;
  }

  public Binding binding() {
    String tenant = s(task, "tenant_id"),
        id = s(task, "id"),
        binding = records.contract(task).binding();
    return new Binding(tenant, id, binding);
  }

  public void validateGoal() {
    records.contracts.publicText(s(task, "goal"), records.sensitive());
  }

  public void insert(Binding binding) {
    int version = records.version(task);
    String digest = records.policyHash(task, binding.snapshot());
    records
        .historyStorageMapper()
        .create(
            records.appendQuery(),
            version,
            binding.snapshot(),
            digest,
            binding.tenant(),
            binding.task());
  }

  public int initialBytes() {
    return SessionContracts.bytes(s(task, "goal"))
        + SessionContracts.bytes(s(task, "request_snapshot"));
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public int add(long additional) {
    String tenant = s(task, "tenant_id"), id = s(task, "id");
    return records.historyStorageMapper().body(records.appendQuery(), additional, tenant, id);
  }
}
