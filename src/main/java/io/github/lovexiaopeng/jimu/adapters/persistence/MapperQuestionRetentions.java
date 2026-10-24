package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.entity.QuestionBodyReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.entity.QuestionInvocationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.entity.QuestionStepReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionBodyReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionRetentionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionStepReadMapper;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionRetentions;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.List;
import java.util.Map;

/** 同原DS/UoW/显式query；只转换事实、编码原metadata并执行具名MP/XML。 */
final class MapperQuestionRetentions implements QuestionRetentions {
  private final JdbcLedger ledger;
  private final QuestionRetentionMapper sql;

  MapperQuestionRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(QuestionRetentionMapper.class);
  }

  private List<Map<String, Object>> select(Class<?> mapper, Object wrapper) {
    var params = new java.util.HashMap<String, Object>();
    params.put("ew", wrapper);
    params.put("query", ledger.queryContext());
    return ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", params);
  }

  private static List<Body> bodies(Map<String, Object> row, String... fields) {
    return java.util.Arrays.stream(fields).map(field -> new Body(field, s(row, field))).toList();
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(row -> new Candidate(s(row, "tenant_id"), s(row, "id")))
        .toList();
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public String metadata(List<RetainedBody> bodies) {
    var retained = new java.util.LinkedHashMap<String, Object>();
    for (var body : bodies)
      retained.put(body.key(), Map.of("sha256", body.sha256(), "bytes", body.bytes()));
    return Payloads.json(retained);
  }

  public LockedTask lockTask(Candidate candidate) {
    var row = ledger.locked(candidate.tenant(), candidate.taskId());
    return new LockedTask() {
      public String cleanupState() {
        return s(row, "question_cleanup_state");
      }

      public Tasks.View view() {
        return ledger.view(row);
      }

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(row);
      }

      public boolean unsafeReservations() {
        return !sql.unsafeReservations(
                ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public boolean unknownInvocations() {
        return !sql.unknownInvocations(
                ledger.queryContext(), candidate.tenant(), candidate.taskId())
            .isEmpty();
      }

      public List<Body> bodies() {
        return MapperQuestionRetentions.bodies(row, "request_snapshot", "goal", "summary");
      }

      public List<Content> steps() {
        var wrapper = new LambdaQueryWrapper<QuestionStepReadEntity>();
        wrapper
            .eq(QuestionStepReadEntity::getTenantId, candidate.tenant())
            .eq(QuestionStepReadEntity::getTaskId, candidate.taskId());
        return select(QuestionStepReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperQuestionRetentions.bodies(
                            r, "request_snapshot", "response_snapshot")))
            .toList();
      }

      public List<Content> invocations() {
        var wrapper = new LambdaQueryWrapper<QuestionInvocationReadEntity>();
        wrapper
            .eq(QuestionInvocationReadEntity::getTenantId, candidate.tenant())
            .eq(QuestionInvocationReadEntity::getTaskId, candidate.taskId());
        return select(QuestionInvocationReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperQuestionRetentions.bodies(r, "input_snapshot", "output_snapshot")))
            .toList();
      }

      public List<Content> questions() {
        var wrapper = new LambdaQueryWrapper<QuestionBodyReadEntity>();
        wrapper
            .eq(QuestionBodyReadEntity::getTenantId, candidate.tenant())
            .eq(QuestionBodyReadEntity::getTaskId, candidate.taskId());
        return select(QuestionBodyReadMapper.class, wrapper).stream()
            .map(
                r ->
                    new Content(
                        s(r, "rowId"),
                        MapperQuestionRetentions.bodies(
                            r, "request_snapshot", "answer_snapshot", "receipt_snapshot")))
            .toList();
      }

      public void purgeStep(String id, String tombstone) {
        sql.purgeStep(ledger.queryContext(), tombstone, candidate.tenant(), id);
      }

      public void purgeInvocation(String id, String tombstone) {
        sql.purgeInvocation(ledger.queryContext(), tombstone, candidate.tenant(), id);
      }

      public void purgeQuestion(String id, String tombstone, String retained) {
        sql.purgeQuestion(ledger.queryContext(), tombstone, retained, candidate.tenant(), id);
      }

      public void purgeTask(String tombstone, String retained) {
        sql.purgeTask(
            ledger.queryContext(), tombstone, retained, candidate.tenant(), candidate.taskId());
      }

      public int releaseHold() {
        return sql.releaseHold(
            ledger.queryContext(), candidate.tenant(), candidate.taskId() + ":question-storage");
      }

      public void releaseBudget() {
        sql.releaseBudget(ledger.queryContext(), candidate.tenant());
      }

      public void event() {
        ledger.event(row, "question-purged", "question.content_deleted", null);
      }
    };
  }
}
