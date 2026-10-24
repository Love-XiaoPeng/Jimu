package io.github.lovexiaopeng.jimu.kernel.invocation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationRecords;
import io.github.lovexiaopeng.jimu.kernel.invocation.port.InvocationWorkflowRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.LeaseUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Rule precedence, lazy facts and commit-after-refusal at the actual Service boundary. */
class InvocationRulesTest {
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Object> facts = new HashMap<>();
  private String protectedTool;
  private boolean committed;
  private final Lease lease =
      new Lease(
          "t", "a", "task", "attempt", 1, "worker", Instant.MAX, "goal", List.of("web_fetch"), 1);

  @Test
  void protectedCommitStopsBeforeTransactionAndLaterQueries() {
    protectedTool = "session_event_read";
    reject("SESSION_READ_COMMIT_REQUIRED", () -> service().uncertain(lease, "i"));
    assertEquals(
        List.of(
            "guard:session_event_trace", "guard:session_event_search", "guard:session_event_read"),
        calls);
  }

  @Test
  void scopeRefusalDoesNotQueryOrStartTransaction() {
    var scope =
        new Lease(
            "t",
            "a",
            "task",
            "attempt",
            1,
            "worker",
            Instant.MAX,
            "goal",
            List.of("session_search"),
            1);
    reject("SESSION_SCOPE_COMMIT_REQUIRED", () -> service().succeeded(scope, "i", "out"));
    assertTrue(calls.isEmpty());
  }

  @Test
  void unknownKeepsHoldAndNeverCallsSuccess() {
    service().uncertain(lease, "i");
    assertTrue(calls.indexOf("markUnknown") < calls.indexOf("holdUnknown"));
    assertTrue(calls.indexOf("holdUnknown") < calls.indexOf("reconciliationRequired"));
    assertFalse(calls.contains("settleUnit"));
    assertTrue(committed);
  }

  @Test
  void writeRequiresReadBeforeTargetAndPin() {
    setupAdmission("write");
    facts.put(
        "materialUse",
        new InvocationWorkflowRecords.MaterialUse(Set.of(), "expected", "", "wrong", 0, null));
    reject(
        "EVALUATION_MATERIAL_MISMATCH",
        () -> service().admit(lease, "s", new Call("slot", "p", "write", "in"), "v", "d"));
    assertTrue(calls.contains("hasSuccessfulRead"));
    assertFalse(calls.contains("pin"));
    assertFalse(calls.contains("reserveUnit"));
  }

  @Test
  void noMaterialDoesNotReadHistoryAndSuccessfulSlotDoesNotSpendAgain() {
    setupAdmission("web_fetch");
    facts.put(
        "slot",
        new InvocationWorkflowRecords.Slot(
            "i", "e", "web_fetch", "p", "in", "SUCCEEDED", "out", "out"));
    assertEquals(
        "out",
        service().admit(lease, "s", new Call("slot", "p", "web_fetch", "in"), "v", "d").output());
    assertFalse(calls.contains("hasSuccessfulRead"));
    assertFalse(calls.contains("reserveUnit"));
  }

  @Test
  void imageMismatchPrecedesWebProofAndSettlement() {
    facts.put("tool", "read_image");
    facts.put("imageProof", new InvocationWorkflowRecords.ImageProof(List.of("actual"), "forged"));
    reject("IMAGE_COMMIT_REQUIRED", () -> service().commit(lease, "i", "out", false));
    assertFalse(calls.contains("webProof"));
    assertFalse(calls.contains("succeed"));
    assertFalse(committed);
  }

  @Test
  void webOutputMismatchDoesNotComputeProofOrSettle() {
    facts.put("tool", "web_fetch");
    facts.put("webProof", new InvocationWorkflowRecords.WebProof("different", "out", "receipt"));
    reject("WEB_RESULT_PROOF_INVALID", () -> service().commit(lease, "i", "out", false));
    assertFalse(calls.contains("webProofDigest"));
    assertFalse(calls.contains("succeed"));
  }

  @Test
  void postMaterialRefusalCommitsSuccessAndStopBeforeThrowing() {
    facts.put("tool", "web_fetch");
    facts.put("succeed", 1);
    facts.put("validateMaterial", new TaskFailure(409, "EVALUATION_MATERIAL_MISMATCH"));
    reject("EVALUATION_MATERIAL_MISMATCH", () -> service().commit(lease, "i", "out", false));
    assertTrue(committed);
    assertTrue(calls.indexOf("settleUnit") < calls.indexOf("stopEvaluationRun"));
    assertTrue(calls.contains("succeeded"));
  }

  private void setupAdmission(String tool) {
    facts.put(
        "slot",
        new InvocationWorkflowRecords.Slot("i", "e", tool, "p", "in", "PENDING", null, null));
    facts.put("pin", new InvocationWorkflowRecords.Pin("v", "d", 1, "v", "d", 1, "v", "d"));
    facts.put("maxTools", 10L);
    facts.put("tenantToolLimit", 10L);
  }

  private InvocationServiceImpl service() {
    var records =
        new InvocationRecords() {
          public boolean protectedInvocation(Lease ignored, String invocation, String tool) {
            calls.add("guard:" + tool);
            return tool.equals(protectedTool);
          }

          public Invocation lockSettlement(Lease ignored, String invocation) {
            calls.add("lockSettlement");
            return proxy(Invocation.class);
          }
        };
    var workflows =
        new InvocationWorkflowRecords() {
          public Admission lockAdmission(
              Lease ignored, String step, Call call, String version, String digest) {
            return proxy(Admission.class);
          }

          public Success lockSuccess(Lease ignored, String invocation, String output) {
            return proxy(Success.class);
          }
        };
    var work =
        new LedgerUnitOfWork() {
          public <T> T required(Supplier<T> action) {
            calls.add("transaction");
            var result = action.get();
            committed = true;
            return result;
          }
        };
    var admissions =
        new LeaseUnitOfWork() {
          public <T> T admission(Lease ignored, Supplier<T> action) {
            return action.get();
          }

          public <T> T directory(Lease ignored, Supplier<T> action) {
            return action.get();
          }
        };
    return new InvocationServiceImpl(records, work, workflows, admissions, value -> value);
  }

  private <T> T proxy(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, a) -> {
              calls.add(m.getName());
              var value = facts.get(m.getName());
              if (value instanceof RuntimeException failure) throw failure;
              if (value != null) return value;
              if (m.getReturnType() == boolean.class) return false;
              if (m.getReturnType() == int.class) return 0;
              if (m.getReturnType() == long.class) return 0L;
              return null;
            }));
  }

  private static void reject(String code, Runnable action) {
    assertEquals(code, assertThrows(TaskFailure.class, action::run).code());
  }
}
