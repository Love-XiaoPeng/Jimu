package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.RuntimeEvaluationPlanCodec;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Selection;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords.Room;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords.Run;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords.Screening;
import io.github.lovexiaopeng.jimu.kernel.model.service.EvaluationBindingService.StoredBinding;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 冻结边界、拒绝优先级和惰性查询/锁顺序；MySQL 证据由定向集成补充。 */
class EvaluationBindingServiceTest {
  private final EvaluationBindingServiceImpl service =
      new EvaluationBindingServiceImpl(new RuntimeEvaluationPlanCodec());
  private final Plan plan = EvaluationPlans.plan("qwen-main-v1");
  private final String model = "qwen3.8-max-0902";

  @Test
  void unconfiguredFixtureNeedsNoRecordsOrCodec() {
    var facts = new Facts();
    facts.run = null;
    facts.selection = null;
    assertNull(service.bind(facts, null));
    service.check(facts, new StoredBinding(null, null, null, null, null), null);
    assertEquals(List.of(), facts.calls);
  }

  @Test
  void digestIsCheckedBeforeJsonAndConflictQueries() {
    var facts = new Facts();
    facts.run =
        run(
            "not-json",
            "wrong",
            2,
            plan.maxRequests(),
            plan.maxCostMicros(),
            0,
            0,
            "OPEN",
            null,
            null);
    rejected("EVALUATION_CONFIG_UNAVAILABLE", () -> service.plan(facts));
    assertEquals(List.of(), facts.calls);
  }

  @Test
  void canonicalPolicyCapsAndConflictingBindingsFailClosed() {
    for (var run :
        List.of(
            run(
                json(),
                digest(),
                1,
                plan.maxRequests(),
                plan.maxCostMicros(),
                0,
                0,
                "OPEN",
                null,
                null),
            run(
                json(),
                digest(),
                2,
                plan.maxRequests() + 1,
                plan.maxCostMicros(),
                0,
                0,
                "OPEN",
                null,
                null),
            run(
                json(),
                digest(),
                2,
                plan.maxRequests(),
                plan.maxCostMicros() + 1,
                0,
                0,
                "OPEN",
                null,
                null))) {
      var facts = new Facts();
      facts.run = run;
      rejected("EVALUATION_CONFIG_UNAVAILABLE", () -> service.plan(facts));
      assertEquals(List.of(), facts.calls);
    }
    var facts = new Facts();
    facts.conflict = true;
    rejected("EVALUATION_CONFIG_UNAVAILABLE", () -> service.bind(facts, snapshot(false)));
    assertEquals(List.of("conflict"), facts.calls);
  }

  @Test
  void initialEnvelopeFitsExactlyAndLocksTenantBeforeRefusingRunCapacity() {
    var facts = new Facts();
    facts.task = false;
    facts.room =
        new Room(plan.matrixWorstCostMicros(), plan.tenantTokenLimit(), plan.maxRequests());
    assertEquals("A", service.bind(facts, snapshot(false)).phase());
    assertEquals(List.of("conflict", "profile", "task", "tenant"), facts.calls);
    facts.calls.clear();
    facts.run =
        run(
            json(),
            digest(),
            2,
            plan.maxRequests(),
            plan.maxCostMicros(),
            1,
            0,
            "OPEN",
            null,
            null);
    rejected("EVALUATION_MATRIX_BUDGET_UNAVAILABLE", () -> service.bind(facts, snapshot(false)));
    assertEquals(List.of("conflict", "profile", "task", "tenant"), facts.calls);
  }

  @Test
  void eachTenantEnvelopeRefusesOneBelowFullMatrix() {
    for (var room :
        List.of(
            new Room(plan.matrixWorstCostMicros() - 1, plan.tenantTokenLimit(), plan.maxRequests()),
            new Room(plan.matrixWorstCostMicros(), plan.tenantTokenLimit() - 1, plan.maxRequests()),
            new Room(
                plan.matrixWorstCostMicros(), plan.tenantTokenLimit(), plan.maxRequests() - 1))) {
      var facts = new Facts();
      facts.task = false;
      facts.room = room;
      rejected("EVALUATION_MATRIX_BUDGET_UNAVAILABLE", () -> service.bind(facts, snapshot(false)));
    }
  }

  @Test
  void streamMismatchFollowsInitialEnvelopeCheck() {
    var facts = new Facts();
    facts.task = false;
    rejected("EVALUATION_CONFIG_UNAVAILABLE", () -> service.bind(facts, snapshot(true)));
    assertEquals(List.of("conflict", "profile", "task", "tenant"), facts.calls);
  }

  @Test
  void unknownRefusesBeforeQualificationAndScreeningReads() {
    var facts = phaseB();
    facts.unknown = true;
    rejected("EVALUATION_PHASE_LOCKED", () -> service.bind(facts, snapshot(false)));
    assertEquals(List.of("conflict", "profile", "task", "unknown"), facts.calls);
  }

  @Test
  void screeningNeedsExactlyOneTerminalPerCandidateAndSuccessForFinalist() {
    for (int mode : List.of(0, 1, 2)) {
      var facts = phaseB();
      facts.screening = new ArrayList<>(facts.screening);
      if (mode == 0) facts.screening.add(facts.screening.getFirst());
      if (mode == 1) facts.screening.removeFirst();
      if (mode == 2) {
        facts.screening.removeIf(row -> row.model().equals(model));
        facts.screening.add(new Screening(model, "W1", "FAILED"));
        facts.screening.add(new Screening(model, "W2", "SUCCEEDED"));
      }
      rejected("EVALUATION_PHASE_LOCKED", () -> service.bind(facts, snapshot(false)));
      assertEquals(List.of("conflict", "profile", "task", "unknown", "screening"), facts.calls);
    }
  }

  @Test
  void firstPhaseBRequiresRemainingEnvelopeAndLaterBindingsDoNotRelockTenant() {
    var facts = phaseB();
    long cost = 2 * EvaluationPlan.taskCost(plan, model, "W3");
    facts.phaseB = false;
    facts.room = new Room(cost, 12 * 1004096L, 12);
    assertEquals(
        facts.run.qualificationDigest(),
        service.bind(facts, snapshot(false)).qualificationDigest());
    assertEquals(
        List.of("conflict", "profile", "task", "unknown", "screening", "phaseB", "tenant"),
        facts.calls);
    facts.calls.clear();
    facts.room = new Room(cost, 12 * 1004096L - 1, 12);
    rejected("EVALUATION_MATRIX_BUDGET_UNAVAILABLE", () -> service.bind(facts, snapshot(false)));
    facts.calls.clear();
    facts.phaseB = true;
    service.bind(facts, snapshot(false));
    assertEquals(
        List.of("conflict", "profile", "task", "unknown", "screening", "phaseB"), facts.calls);
  }

  @Test
  void configuredFailurePrecedesCorruptTaskAndFrozenColumnsCannotDrift() {
    var facts = new Facts();
    var binding = service.bind(facts, snapshot(false));
    var corrupt = new StoredBinding("not-json", "wrong", "A", "W1", model);
    facts.conflict = true;
    rejected("EVALUATION_CONFIG_UNAVAILABLE", () -> service.check(facts, corrupt, snapshot(false)));
    facts.conflict = false;
    rejected("SNAPSHOT_CORRUPT", () -> service.check(facts, corrupt, snapshot(false)));
    String json = Payloads.json(binding);
    service.check(
        facts, new StoredBinding(json, Payloads.hash(json), "A", "W1", model), snapshot(false));
    rejected(
        "EVALUATION_CONFIG_UNAVAILABLE",
        () ->
            service.check(
                facts,
                new StoredBinding(json, Payloads.hash(json), "B", "W1", model),
                snapshot(false)));
    rejected(
        "EVALUATION_CONFIG_UNAVAILABLE",
        () ->
            service.check(facts, new StoredBinding(null, null, null, null, null), snapshot(false)));
  }

  private Facts phaseB() {
    var facts = new Facts();
    facts.selection = new Selection(plan, "B", "W3");
    var grades = new HashMap<String, EvaluationPlan.Assessment>();
    var score = new EvaluationPlan.Score(18, 28, 28, 18, false);
    for (String candidate : plan.candidates().keySet())
      grades.put(
          candidate, new EvaluationPlan.Assessment(true, true, 18, score, score, "a".repeat(64)));
    var q =
        new EvaluationPlan.Qualification(
            digest(),
            plan.materialsDigest(),
            grades,
            List.of(model),
            true,
            "b".repeat(40),
            "c".repeat(64),
            "synthetic",
            "2026-10-24T00:00:00Z",
            "run");
    String json = Payloads.json(q);
    facts.run =
        run(
            json(),
            digest(),
            2,
            plan.maxRequests(),
            plan.maxCostMicros(),
            0,
            0,
            "OPEN",
            json,
            Payloads.hash(json));
    var screening = new ArrayList<Screening>();
    for (String candidate : plan.candidates().keySet())
      for (String sample : List.of("W1", "W2"))
        screening.add(
            new Screening(candidate, sample, candidate.equals(model) ? "SUCCEEDED" : "FAILED"));
    facts.screening = screening;
    return facts;
  }

  private String json() {
    return Payloads.json(plan);
  }

  private String digest() {
    return EvaluationPlans.digest(plan);
  }

  private static Run run(
      String snapshot,
      String digest,
      long version,
      long requests,
      long cost,
      long used,
      long held,
      String state,
      String qualification,
      String qualificationDigest) {
    return new Run(
        "run",
        snapshot,
        digest,
        version,
        requests,
        cost,
        used,
        held,
        state,
        qualification,
        qualificationDigest);
  }

  private ModelSnapshot snapshot(boolean stream) {
    return new ModelSnapshot(
        2,
        "test",
        1,
        "openai-chat-v1",
        "http://127.0.0.1",
        model,
        model,
        "none",
        null,
        0,
        "trusted-local-v1",
        2000,
        20000,
        1048576,
        stream,
        4096,
        "max_completion_tokens",
        10,
        null,
        Map.of(),
        true,
        null,
        null);
  }

  private static void rejected(String code, Runnable action) {
    assertEquals(code, assertThrows(TaskFailure.class, action::run).code());
  }

  private final class Facts implements EvaluationBindingRecords {
    private Run run =
        EvaluationBindingServiceTest.run(
            json(),
            digest(),
            2,
            plan.maxRequests(),
            plan.maxCostMicros(),
            0,
            0,
            "OPEN",
            null,
            null);
    private Selection selection = new Selection(plan, "A", "W1");
    private Room room = new Room(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    private boolean conflict, unknown;
    private boolean task = true, phaseB = true;
    private List<Screening> screening = List.of();
    private final List<String> calls = new ArrayList<>();

    public Run run() {
      return run;
    }

    public Selection selection() {
      return selection;
    }

    public void validateModel(Plan plan, ModelSnapshot model) {
      calls.add("profile");
    }

    public boolean conflictingBinding() {
      calls.add("conflict");
      return conflict;
    }

    public boolean hasTask() {
      calls.add("task");
      return task;
    }

    public boolean hasUnknown() {
      calls.add("unknown");
      return unknown;
    }

    public List<Screening> screening() {
      calls.add("screening");
      return screening;
    }

    public boolean hasPhaseBTask() {
      calls.add("phaseB");
      return phaseB;
    }

    public Room lockTenantRoom() {
      calls.add("tenant");
      return room;
    }
  }
}
