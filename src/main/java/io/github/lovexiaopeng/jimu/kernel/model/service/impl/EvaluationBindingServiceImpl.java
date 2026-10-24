package io.github.lovexiaopeng.jimu.kernel.model.service.impl;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Binding;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Plan;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.Qualification;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationBindingRecords.Run;
import io.github.lovexiaopeng.jimu.kernel.model.port.EvaluationPlanCodec;
import io.github.lovexiaopeng.jimu.kernel.model.service.EvaluationBindingService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;

/** 共享评估绑定规则；只在原事务内按原分支访问事实，不提交、不重试、不新增授权。 */
public final class EvaluationBindingServiceImpl implements EvaluationBindingService {
  private final EvaluationPlanCodec codec;

  public EvaluationBindingServiceImpl(EvaluationPlanCodec codec) {
    this.codec = codec;
  }

  @Override
  public Plan plan(EvaluationBindingRecords records) {
    var run = records.run();
    if (run == null || run.snapshot() == null) return null;
    if (!codec.hash(run.snapshot()).equals(run.digest())) throw unavailable();
    var stored = codec.readPlan(run.snapshot());
    var canonical = codec.canonical(stored.id());
    if (!stored.equals(canonical)
        || run.policyVersion() != 2
        || run.maxRequests() > canonical.maxRequests()
        || run.maxCost() > canonical.maxCostMicros()) throw unavailable();
    if (records.conflictingBinding()) throw unavailable();
    return canonical;
  }

  @Override
  public Binding bind(EvaluationBindingRecords records, ModelSnapshot model) {
    var selection = records.selection();
    var plan = plan(records);
    if (plan == null && selection == null) return null;
    if (plan == null || selection == null || !plan.equals(selection.plan()) || !model.paid())
      throw unavailable();
    records.validateModel(plan, model);
    var run = records.run();
    if (!records.hasTask() && "OPEN".equals(run.state()))
      checkRoom(
          records, run, plan.maxRequests(), plan.matrixWorstCostMicros(), plan.tenantTokenLimit());
    if (model.stream() != List.of("W2", "W4").contains(selection.sample())) throw unavailable();
    String qualification = null;
    if (selection.phase().equals("B")) {
      if (!"OPEN".equals(run.state()) || records.hasUnknown()) throw phaseLocked();
      var q = qualify(plan, run.qualificationSnapshot(), run.qualificationDigest());
      if (!q.originalRunId().equals(run.id()) || !q.finalists().contains(model.model()))
        throw phaseLocked();
      var screening = records.screening();
      for (String candidate : plan.candidates().keySet())
        for (String sample : List.of("W1", "W2")) {
          var match =
              screening.stream()
                  .filter(t -> candidate.equals(t.model()) && sample.equals(t.sample()))
                  .toList();
          if (match.size() != 1
              || !List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(match.getFirst().state())
              || q.finalists().contains(candidate) && !"SUCCEEDED".equals(match.getFirst().state()))
            throw phaseLocked();
        }
      if (!records.hasPhaseBTask()) {
        long rounds = 12L * q.finalists().size();
        long remainingCost =
            q.finalists().stream()
                .mapToLong(
                    candidate ->
                        2
                            * io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan.taskCost(
                                plan, candidate, "W3"))
                .sum();
        checkRoom(records, run, rounds, remainingCost, rounds * 1004096);
      }
      qualification = run.qualificationDigest();
    }
    return new Binding(
        plan.id(),
        run.digest(),
        selection.phase(),
        selection.sample(),
        model.model(),
        qualification,
        run.maxRequests(),
        run.maxCost());
  }

  private static void checkRoom(
      EvaluationBindingRecords records, Run run, long requests, long cost, long tokens) {
    // 原实现先锁 tenant 再判定，即使 run 额度已不足也不能提前返回改变锁序。
    var tenant = records.lockTenantRoom();
    if (run.requestsUsed() + requests > run.maxRequests()
        || cost > run.maxCost() - run.costHeld()
        || cost > tenant.cost()
        || tokens > tenant.tokens()
        || requests > tenant.models())
      throw new TaskFailure(429, "EVALUATION_MATRIX_BUDGET_UNAVAILABLE");
  }

  @Override
  public void check(EvaluationBindingRecords records, StoredBinding stored, ModelSnapshot model) {
    // 先校验当前配置/资格，再读 task 快照，保留原拒绝优先级及查询/加锁次序。
    var configured = bind(records, model);
    if (stored.snapshot() == null) {
      if (configured != null) throw unavailable();
      return;
    }
    if (!codec.hash(stored.snapshot()).equals(stored.digest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var binding = codec.readBinding(stored.snapshot());
    if (!binding.equals(configured)
        || !binding.phase().equals(stored.phase())
        || !binding.sample().equals(stored.sample())
        || !binding.model().equals(stored.model())) throw unavailable();
  }

  @Override
  public Qualification qualify(Plan plan, String json, String digest) {
    if (json == null || !codec.hash(json).equals(digest)) throw phaseLocked();
    var q = codec.readQualification(json);
    if (!codec.digest(plan).equals(q.planDigest())
        || !plan.materialsDigest().equals(q.materialsDigest())
        || q.assessments() == null
        || !q.assessments().keySet().equals(plan.candidates().keySet())
        || q.finalists() == null
        || q.finalists().isEmpty()
        || q.finalists().size() > 2
        || q.finalists().stream().distinct().count() != q.finalists().size()) throw phaseLocked();
    if (!q.adjudicated()
        || q.acceptedFinalCodeSha() == null
        || !q.acceptedFinalCodeSha().matches("[0-9a-f]{40}")
        || q.evidenceManifestDigest() == null
        || !q.evidenceManifestDigest().matches("[0-9a-f]{64}")
        || q.actor() == null
        || q.actor().isBlank()
        || q.timestamp() == null
        || q.originalRunId() == null
        || q.originalRunId().isBlank()) throw phaseLocked();
    java.time.Instant.parse(q.timestamp());
    for (var assessment : q.assessments().values())
      if (assessment == null
          || !assessment.completed()
          || assessment.evidenceSha256() == null
          || !assessment.evidenceSha256().matches("[0-9a-f]{64}")) throw phaseLocked();
    for (String finalist : q.finalists())
      if (!q.assessments().containsKey(finalist) || !q.assessments().get(finalist).qualifies())
        throw phaseLocked();
    return q;
  }

  private static TaskFailure unavailable() {
    return new TaskFailure(409, "EVALUATION_CONFIG_UNAVAILABLE");
  }

  private static TaskFailure phaseLocked() {
    return new TaskFailure(409, "EVALUATION_PHASE_LOCKED");
  }
}
