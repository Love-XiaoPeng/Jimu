package io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface TaskAttemptMapper {
  List<Map<String, Object>> selectWorkerTasks(
      @Param("query") SessionQueryContext query, @Param("worker") String worker);

  List<Map<String, Object>> selectActiveNativeAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId);

  int markOwnedAttemptQuiescent(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId,
      @Param("worker") String worker,
      @Param("fence") long fence);

  int markWorkerTaskAttemptsQuiescent(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("worker") String worker);

  int expireActiveAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId);

  long countTaskAttempts(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int insertActiveAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId,
      @Param("taskId") String taskId,
      @Param("worker") String worker,
      @Param("fence") long fence);

  List<Map<String, Object>> selectUnobservedWriters(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectActiveNativeTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> directorySourceAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("attempt") String attempt,
      @Param("fence") long fence);

  List<Map<String, Object>> directoryViewAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("attempt") String attempt,
      @Param("fence") long fence);

  java.util.List<java.util.Map<String, Object>> metadataRows(
      @org.apache.ibatis.annotations.Param("query")
          io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      @org.apache.ibatis.annotations.Param("tenant") String tenant,
      @org.apache.ibatis.annotations.Param("task") String task);
}
