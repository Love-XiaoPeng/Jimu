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
}
