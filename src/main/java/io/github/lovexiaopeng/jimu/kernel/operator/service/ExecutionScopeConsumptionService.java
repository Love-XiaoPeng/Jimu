package io.github.lovexiaopeng.jimu.kernel.operator.service;

import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords.Policy;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords.Requirement;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;

public interface ExecutionScopeConsumptionService {
  String targetRun(String tenant, String fallback);

  boolean needed(String tenant, Map<String, Object> task);

  Requirement requirement(String tenant);

  void businessAnchor(Policy policy, Map<String, Object> run);

  String require(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model);

  String requireScope(
      String tenant, Map<String, Object> evaluation, Map<String, Object> task, ModelSnapshot model);

  boolean businessTask(Map<String, Object> task);

  void requireBusiness(
      String tenant, Map<String, Object> run, Map<String, Object> task, ModelSnapshot model);
}
