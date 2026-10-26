package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeConsumptionService;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;

/** Original package call shapes; qualification is owned by the consumption service. */
final class JdbcExecutionScopes {
  private final ExecutionScopeConsumptionService service;

  JdbcExecutionScopes(ExecutionScopeConsumptionService service) {
    this.service = service;
  }

  String targetRun(String tenant, String fallback) {
    return service.targetRun(tenant, fallback);
  }

  boolean needed(String tenant, Map<String, Object> task) {
    return service.needed(tenant, task);
  }

  ExecutionScopes.Requirement requirement(String tenant) {
    return MapperExecutionScopeConsumption.unwrap(service.requirement(tenant));
  }

  void businessAnchor(BusinessPolicies.Policy policy, Map<String, Object> run) {
    service.businessAnchor(MapperExecutionScopeConsumption.wrap(policy), run);
  }

  String require(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model) {
    return service.require(tenant, run, task, model);
  }

  static boolean matches(ExecutionScopes.Requirement requirement, ExecutionScopes.Scope scope) {
    return io.github.lovexiaopeng.jimu.kernel.operator.service.impl
        .ExecutionScopeConsumptionServiceImpl.matches(
        MapperExecutionScopeConsumption.wrap(requirement),
        MapperExecutionScopeConsumption.wrap(scope));
  }
}
