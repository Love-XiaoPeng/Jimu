package io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 锁/条件写入/账本结算；只在原同DS事务与锁序内调用。 */
@Mapper
public interface TaskOperationsMapper {
  /** 已持task锁；原published产物清单物化前的真实行数及文本UTF8长度。 */
  List<Map<String, Object>> publishedArtifactSizes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int requestCancelIntent(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int requestCancel(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  long oldestEvent(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> eventPage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("after") long after,
      @Param("limit") int limit);

  List<Map<String, Object>> lockQuestions(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int closeQuestion(
      @Param("query") SessionQueryContext query,
      @Param("code") String code,
      @Param("tenant") String tenant,
      @Param("questionId") String questionId);

  int stopQuestionInvocation(
      @Param("query") SessionQueryContext query,
      @Param("state") String state,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId);

  int settleQuestion(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId);

  int discardQuestionDelivery(
      @Param("query") SessionQueryContext query,
      @Param("code") String code,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int clearQuestionContinuation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int setTerminal(
      @Param("query") SessionQueryContext query,
      @Param("state") String state,
      @Param("code") String code,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markUnknownInvocations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int holdUnknownTools(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int releaseReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int releaseQueue(
      @Param("query") SessionQueryContext query,
      @Param("count") long count,
      @Param("tenant") String tenant);

  int releaseRun(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int releaseQuestionTask(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int holdUnknownModels(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int stopAttempt(
      @Param("query") SessionQueryContext query,
      @Param("state") String state,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId);
}
