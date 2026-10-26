package io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface TaskMapper {
  int renewOwnedLease(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("attemptId") String attemptId,
      @Param("worker") String worker,
      @Param("fence") long fence);

  int expireStoppedWorkerLease(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectTaskForUpdate(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectEvaluationRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectReadyCandidate(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  List<Map<String, Object>> claimExactCandidate(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int assignClaimedLease(
      @Param("query") SessionQueryContext query,
      @Param("fence") long fence,
      @Param("attemptId") String attemptId,
      @Param("worker") String worker,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  long selectNextEventSequence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int advanceEventSequence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  String selectState(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int clearQuestionContinuation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int finishSummary(
      @Param("query") SessionQueryContext query,
      @Param("summary") String summary,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int adjustModelCost(
      @Param("query") SessionQueryContext query,
      @Param("cost") long cost,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int reserveModelTokens(
      @Param("query") SessionQueryContext query,
      @Param("tokens") long tokens,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int releaseModelTokens(
      @Param("query") SessionQueryContext query,
      @Param("tokens") long tokens,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectConflictingEvaluationBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId,
      @Param("planDigest") String planDigest,
      @Param("qualificationDigest") String qualificationDigest);

  List<Map<String, Object>> selectExistingEvaluationTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  List<Map<String, Object>> selectEvaluationScreening(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  List<Map<String, Object>> selectPhaseBEvaluationTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  int stopFurtherModelDispatch(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int reserveToolUnit(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int commitLiveFence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("revision") long revision,
      @Param("attemptId") String attemptId,
      @Param("fence") long fence);

  List<Map<String, Object>> selectWebDiagnosticTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
