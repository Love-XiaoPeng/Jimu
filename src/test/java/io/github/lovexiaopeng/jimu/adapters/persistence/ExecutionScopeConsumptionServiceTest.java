package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.BusinessPolicies;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopes;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ExecutionScopeConsumptionRecords;
import io.github.lovexiaopeng.jimu.kernel.operator.service.impl.ExecutionScopeConsumptionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Local rule gaps: original short circuits, first errors, claims and exact catch boundaries. */
class ExecutionScopeConsumptionServiceTest {
  final List<String> calls = new ArrayList<>();
  final Map<String, RuntimeException> errors = new HashMap<>();
  final String d = Payloads.hash("authority");
  final ExecutionScopes.Requirement req =
      new ExecutionScopes.Requirement(1, "issue", "uuid", "schema", "tenant", "run", d);
  final ModelSnapshot model =
      new ModelSnapshot(
          2,
          "a",
          1,
          "openai-chat-v1",
          "http://127.0.0.1:1",
          "qwen3.7-plus-2026-05-26",
          "qwen3.7-plus-2026-05-26",
          "none",
          null,
          0,
          "trusted-local-v1",
          2000,
          20000,
          1048576,
          false,
          4096,
          "max_completion_tokens",
          10,
          null,
          Map.of(),
          true,
          null,
          null);
  final EvaluationPlan.Plan plan = EvaluationPlans.plan("qwen-main-v1");
  final Map<String, Object> receipt = new HashMap<>();
  final Map<String, Object> row = new HashMap<>();
  final Map<String, Object> run =
      new HashMap<>(Map.of("id", "run", "plan_digest", EvaluationPlans.digest(plan)));
  final Map<String, Object> task =
      new HashMap<>(Map.of("record_version", 1, "fence", 7L, "evaluation_run_id", "run"));
  List<Map<String, Object>> receipts;
  List<String> bound = List.of();
  List<Map<String, Object>> rows;
  ExecutionScopeConsumptionRecords.Requirement configured =
      MapperExecutionScopeConsumption.wrap(req);
  EvaluationPlan.Selection selection = new EvaluationPlan.Selection(plan, "A", "W1");
  ExecutionScopeConsumptionRecords.Policy policy;
  int tables = 1;
  String uuid = "uuid", schema = "schema", candidate = "qwen3.7-plus-2026-05-26";
  final ExecutionScopeConsumptionRecords port =
      (ExecutionScopeConsumptionRecords)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {ExecutionScopeConsumptionRecords.class},
              (p, m, a) -> {
                calls.add(m.getName());
                if (errors.containsKey(m.getName())) throw errors.get(m.getName());
                return switch (m.getName()) {
                  case "dataAccess" ->
                      new MapperExecutionScopeConsumption(null, null)
                          .dataAccess((Supplier<?>) a[0]);
                  case "receiptTable" -> tables;
                  case "receipts" -> receipts;
                  case "boundRuns" -> bound;
                  case "scopeRequirement" -> configured;
                  case "evaluationSelection" -> selection;
                  case "selected", "historicalSnapshot" -> model;
                  case "localEvaluation" -> true;
                  case "serverUuid" -> uuid;
                  case "schema" -> schema;
                  case "evaluationRunId" -> "run";
                  case "hash" -> Payloads.hash((String) a[0]);
                  case "digest" -> ExecutionScopes.digest((String) a[0]);
                  case "activeCandidate" -> candidate;
                  case "readScope" ->
                      MapperExecutionScopeConsumption.wrap(
                          Payloads.read((String) a[0], ExecutionScopes.Scope.class));
                  case "canonical" ->
                      m.getParameterTypes()[0] == ExecutionScopeConsumptionRecords.Scope.class
                          ? new MapperExecutionScopeConsumption(null, null)
                              .canonical((ExecutionScopeConsumptionRecords.Scope) a[0])
                          : Payloads.json(originalPolicy());
                  case "scopes" -> rows;
                  case "modelDigest" -> ModelProfiles.digest((ModelSnapshot) a[0]);
                  case "plan", "boundPlan" -> plan;
                  case "planDigest" -> EvaluationPlans.digest((EvaluationPlan.Plan) a[0]);
                  case "businessPolicy", "readPolicy" -> policy;
                  case "available",
                          "checkEvaluation",
                          "retainedBusiness",
                          "validate",
                          "profile",
                          "stopRun",
                          "event" ->
                      null;
                  default -> throw new AssertionError(m.getName());
                };
              });
  final ExecutionScopeConsumptionServiceImpl service =
      new ExecutionScopeConsumptionServiceImpl(port);

  ExecutionScopeConsumptionServiceTest() {
    String approval = "approval";
    receipt.putAll(
        Map.of(
            "run_id",
            "run",
            "issue_id",
            "issue",
            "instance_uuid",
            "uuid",
            "approval_snapshot",
            approval,
            "approval_digest",
            Payloads.hash(approval),
            "plan_digest",
            EvaluationPlans.digest(plan),
            "materials_digest",
            plan.materialsDigest()));
    configured =
        MapperExecutionScopeConsumption.wrap(
            new ExecutionScopes.Requirement(
                1, "issue", "uuid", "schema", "tenant", "run", Payloads.hash(approval)));
    String raw = ExecutionScopes.canonical(scope());
    row.putAll(
        Map.of("record_version", 1, "scope_snapshot", raw, "scope_digest", Payloads.hash(raw)));
    task.put("execution_scope_digest", Payloads.hash(raw));
    receipts = List.of(receipt);
    rows = List.of(row);
  }

  ExecutionScopes.Scope scope() {
    return new ExecutionScopes.Scope(
        1,
        "scope",
        "issue",
        "uuid",
        "schema",
        "tenant",
        "run",
        Payloads.hash("approval"),
        EvaluationPlans.digest(plan),
        plan.sourcePlanSha256(),
        plan.materialsDigest(),
        d,
        model.model(),
        model.revision(),
        Map.of(
            "a",
            new ExecutionScopes.Pin(1, false, ModelProfiles.digest(model)),
            "sse",
            new ExecutionScopes.Pin(1, true, d)),
        Map.of("W1", "a", "W2", "sse", "W3", "a", "W4", "sse"),
        true);
  }

  BusinessPolicies.Policy originalPolicy() {
    return new BusinessPolicies.Policy(
        1,
        "p",
        "BUSINESS",
        new ExecutionScopes.Requirement(
            1, "issue", "uuid", "schema", "tenant", "run", Payloads.hash("approval")),
        d,
        Map.of("a", d),
        BusinessPolicies.STRATEGY,
        true);
  }

  TaskFailure code(String expected, Runnable work) {
    var f = assertThrows(TaskFailure.class, work::run);
    assertEquals(expected, f.code());
    return f;
  }

  @Test
  void constructorIsLazy() {
    assertTrue(calls.isEmpty());
    new ExecutionScopeConsumptionServiceImpl(null);
  }

  @Test
  void receiptWinsWithoutConfigOrBoundReads() {
    assertEquals("run", service.targetRun("tenant", "other"));
    assertEquals(List.of("dataAccess", "receiptTable", "receipts"), calls);
  }

  @Test
  void configuredRequirementPrecedesBound() {
    receipts = List.of();
    assertEquals("run", service.targetRun("tenant", null));
    assertFalse(calls.contains("boundRuns"));
  }

  @Test
  void fallbackAndAmbiguity() {
    receipts = List.of();
    configured = null;
    bound = List.of("x", "run");
    assertEquals("run", service.targetRun("tenant", "run"));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.targetRun("tenant", null));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.targetRun("tenant", "absent"));
    bound = List.of();
    assertEquals("fallback", service.targetRun("tenant", "fallback"));
  }

  @Test
  void receiptMultiplicityAndNullScalarKeepFirstErrors() {
    receipts = List.of(receipt, receipt);
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.targetRun("tenant", null));
    errors.put("receiptTable", new NullPointerException("scalar"));
    assertSame(
        errors.get("receiptTable"),
        assertThrows(NullPointerException.class, () -> service.targetRun("tenant", null)));
  }

  @Test
  void neededClaimShortCircuitsEveryIo() {
    assertTrue(service.needed("tenant", task));
    assertTrue(calls.isEmpty());
  }

  @Test
  void neededRequirementOtherTenantAndNoScope() {
    receipts = List.of();
    assertFalse(service.needed("other", null));
    configured = null;
    selection = null;
    assertFalse(service.needed("tenant", null));
    assertFalse(calls.contains("selected"));
  }

  @Test
  void neededSelectionLocalAndBoundShortCircuit() {
    receipts = List.of();
    configured = null;
    assertFalse(service.needed("tenant", null));
    assertTrue(calls.contains("selected"));
    calls.clear();
    bound = List.of("run");
    assertTrue(service.needed("tenant", null));
    assertFalse(calls.contains("evaluationSelection"));
  }

  @Test
  void requirementMetadataShortCircuitAndNullableVersion() {
    assertSame(configured, service.requirement("tenant"));
    assertTrue(calls.indexOf("serverUuid") < calls.indexOf("schema"));
    calls.clear();
    receipt.put("approval_digest", "bad");
    code("EVALUATION_SCOPE_NOT_ALLOWED", () -> service.requirement("tenant"));
    assertFalse(calls.contains("serverUuid"));
  }

  @Test
  void requirementMissingVersionAndCandidate() {
    configured = null;
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.requirement("tenant"));
    configured =
        MapperExecutionScopeConsumption.wrap(
            new ExecutionScopes.Requirement(2, "issue", "uuid", "schema", "tenant", "run", d));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.requirement("tenant"));
    configured =
        MapperExecutionScopeConsumption.wrap(
            new ExecutionScopes.Requirement(
                1, "issue", "uuid", "schema", "tenant", "run", Payloads.hash("approval")));
    candidate = "wrong";
    code("EVALUATION_SCOPE_NOT_ALLOWED", () -> service.requirement("tenant"));
  }

  @Test
  void requireClaimAndSuccessConsumeFirstRow() {
    rows = List.of(row, Map.of());
    assertEquals(row.get("scope_digest"), service.require("tenant", run, task, model));
    assertEquals(row.get("scope_digest"), service.requireScope("tenant", run, task, null));
    assertTrue(calls.contains("historicalSnapshot"));
    assertTrue(calls.contains("checkEvaluation"));
  }

  @Test
  void missingRowsClaimMappingAndLegacyOutsideCatch() {
    rows = List.of();
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.require("tenant", run, null, model));
    code("EVALUATION_SCOPE_MISMATCH", () -> service.require("tenant", run, task, model));
    calls.clear();
    task.remove("execution_scope_digest");
    code("EVALUATION_SCOPE_LEGACY_UNBOUND", () -> service.require("tenant", run, task, model));
    assertTrue(calls.isEmpty());
  }

  @Test
  void invalidVersionHashPinAndClaimFailAtOriginalPosition() {
    row.put("record_version", 2);
    code("EVALUATION_SCOPE_MISMATCH", () -> service.require("tenant", run, null, model));
    row.put("record_version", 1);
    task.put("execution_scope_digest", "bad");
    code("EVALUATION_SCOPE_MISMATCH", () -> service.require("tenant", run, task, model));
    selection = new EvaluationPlan.Selection(plan, "A", "W2");
    code("EVALUATION_SCOPE_NOT_ALLOWED", () -> service.require("tenant", run, null, model));
  }

  @Test
  void nullRecordVersionIsNotTranslated() {
    row.put("record_version", null);
    assertThrows(
        NullPointerException.class, () -> service.requireScope("tenant", run, task, model));
    assertFalse(calls.contains("stopRun"));
  }

  @Test
  void stoppedThenEventAndOriginalFailure() {
    var original = new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH");
    errors.put("historicalSnapshot", original);
    var actual =
        code("EVALUATION_SCOPE_MISMATCH", () -> service.requireScope("tenant", run, task, model));
    assertFalse(actual == original);
    assertTrue(calls.indexOf("stopRun") < calls.indexOf("event"));
  }

  @Test
  void availableAndEvaluationLocalMapping() {
    errors.put("available", new IllegalArgumentException("corrupt"));
    code("EVALUATION_SCOPE_MISMATCH", () -> service.requireScope("tenant", run, task, model));
    assertFalse(calls.contains("checkEvaluation"));
    assertTrue(calls.contains("event"));
  }

  @Test
  void updateFailureReplacesAndBlocksEvent() {
    errors.put("historicalSnapshot", new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH"));
    var original = new IllegalStateException("update failed");
    errors.put("stopRun", original);
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class, () -> service.requireScope("tenant", run, task, model)));
    assertFalse(calls.contains("event"));
  }

  @Test
  void eventFailureReplacesAndNullEvaluationDoesNotReadFields() {
    errors.put("historicalSnapshot", new TaskFailure(409, "EVALUATION_SCOPE_MISMATCH"));
    var original = new IllegalStateException("event failed");
    errors.put("event", original);
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class, () -> service.requireScope("tenant", run, task, model)));
    calls.clear();
    code("EVALUATION_SCOPE_MISMATCH", () -> service.requireScope("tenant", null, task, model));
    assertFalse(calls.contains("stopRun"));
  }

  @Test
  void exactDataAccessMappingBoundary() {
    errors.put("dataAccess", new ExecutionScopeConsumptionRecords.Unavailable());
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.targetRun("tenant", null));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.requireScope("tenant", run, task, model));
    assertFalse(calls.contains("stopRun"));
  }

  @Test
  void businessTaskShortCircuitAndConflictStops() {
    task.put("record_version", 2);
    assertTrue(service.businessTask(task));
    code("BUSINESS_POLICY_CONFLICT", () -> service.requireScope("tenant", run, task, model));
    assertTrue(calls.contains("stopRun"));
    assertTrue(calls.contains("event"));
  }

  @Test
  void businessPolicyLocalCatchPreservesNon409() {
    policy = MapperExecutionScopeConsumption.wrap(originalPolicy());
    var original = new TaskFailure(503, "retained unavailable");
    errors.put("retainedBusiness", original);
    assertSame(
        original,
        assertThrows(TaskFailure.class, () -> service.requireBusiness("tenant", run, null, model)));
    errors.put("retainedBusiness", new TaskFailure(409, "retained conflict"));
    code("BUSINESS_POLICY_CONFLICT", () -> service.requireBusiness("tenant", run, null, model));
  }

  @Test
  void businessNoScopeShortCircuit() {
    receipts = List.of();
    configured = null;
    selection = null;
    assertNull(service.requireScope("tenant", null, null, null));
    assertFalse(calls.contains("historicalSnapshot"));
  }

  @Test
  void nullScopeAndPinMismatchKeepOriginalTranslation() {
    var json =
        Payloads.read(
            (String) row.get("scope_snapshot"),
            com.fasterxml.jackson.databind.node.ObjectNode.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("profilePins").path("a"))
        .put("configVersion", 2);
    String raw = Payloads.json(json);
    row.put("scope_snapshot", raw);
    row.put("scope_digest", Payloads.hash(raw));
    code("EVALUATION_SCOPE_NOT_ALLOWED", () -> service.require("tenant", run, null, model));
    code("EVALUATION_SCOPE_MISMATCH", () -> service.require("tenant", run, task, model));
    row.put("scope_snapshot", "null");
    row.put("scope_digest", Payloads.hash("null"));
    code("EVALUATION_SCOPE_MISMATCH", () -> service.require("tenant", run, null, model));
  }

  @Test
  void realAdapterCatchesOnlyDataAccessAtOriginalBoundaries() {
    var failure = new org.springframework.dao.DataRetrievalFailureException("original");
    errors.put("receiptTable", failure);
    assertSame(
        failure,
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> service.needed("tenant", null)));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.targetRun("tenant", null));
    code("EVALUATION_SCOPE_UNAVAILABLE", () -> service.requireScope("tenant", run, task, model));
    assertFalse(calls.contains("stopRun"));
  }

  @Test
  void frozenCodecNullSemantics() {
    var codec = new MapperExecutionScopeConsumption(null, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.canonical((ExecutionScopeConsumptionRecords.Scope) null));
    assertEquals("null", codec.canonical((ExecutionScopeConsumptionRecords.Policy) null));
  }

  @Test
  void businessAdmissionAndHistoricalVersionBinding() {
    policy = MapperExecutionScopeConsumption.wrap(originalPolicy());
    service.requireBusiness("tenant", run, null, model);
    assertTrue(calls.contains("retainedBusiness"));
    assertTrue(calls.contains("profile"));
    assertTrue(calls.contains("available"));
    calls.clear();
    task.put("record_version", 2);
    task.remove("execution_scope_digest");
    String raw = Payloads.json(originalPolicy());
    task.put("business_policy_snapshot", raw);
    task.put("business_policy_digest", Payloads.hash(raw));
    assertNull(service.requireScope("tenant", run, task, null));
    assertTrue(calls.contains("historicalSnapshot"));
    assertTrue(calls.contains("profile"));
    assertFalse(calls.contains("stopRun"));
    task.put("record_version", 3);
    code("BUSINESS_POLICY_CONFLICT", () -> service.requireScope("tenant", run, task, null));
    assertTrue(calls.contains("stopRun"));
    assertTrue(calls.contains("event"));
  }

  @Test
  void outerCatchRethrowsOriginalAndSuppressesEventWithoutTask() {
    var original = new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
    errors.put("businessPolicy", original);
    assertSame(
        original,
        assertThrows(TaskFailure.class, () -> service.requireScope("tenant", run, null, null)));
    assertTrue(calls.contains("stopRun"));
    assertFalse(calls.contains("event"));
  }
}
