package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes;
import io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeService;
import io.github.lovexiaopeng.jimu.kernel.operator.service.impl.ExecutionScopeServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;
import javax.sql.DataSource;

/** 兼容原受信CLI输入与构造器；只负责同DS装配和值适配，无SQL/绑定规则。 */
public final class ExecutionScopeBinding {
  public record Input(
      ExecutionScopes.Scope scope,
      Map<String, ModelSnapshot> profiles,
      ExecutionScopes.AccountEvidence accountEvidence,
      String boundBy) {}

  private final DataSource source;
  private final ModelProfiles models;
  private final ExecutionScopes.Requirement requirement;

  public ExecutionScopeBinding(DataSource source, ModelProfiles models) {
    this.source = source;
    this.models = models;
    requirement = null;
  }

  public ExecutionScopeBinding(DataSource source, ExecutionScopes.Requirement requirement) {
    this.source = source;
    this.models = null;
    this.requirement = requirement;
  }

  public Map<String, Object> bindOnce(Input input) {
    var session = LedgerMapperSessions.createOperator(source);
    var query =
        new SessionQueryContext(
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10), null);
    var service =
        new ExecutionScopeServiceImpl(
            new MapperScopeBindingRecords(session, query),
            new OperatorScopeCodec(models),
            new OperatorTransactions(source, false),
            () -> {
              var req = models == null ? requirement : models.scopeRequirement();
              return req == null
                  ? null
                  : Payloads.read(Payloads.json(req), OperatorScopes.Requirement.class);
            });
    return service.bindOnce(
        input == null
            ? null
            : Payloads.read(Payloads.json(input), ExecutionScopeService.Input.class));
  }
}
