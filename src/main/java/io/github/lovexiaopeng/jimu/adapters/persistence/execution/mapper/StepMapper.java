package io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface StepMapper {
  List<Map<String, Object>> selectLatestStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int insertPreparedStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId,
      @Param("taskId") String taskId,
      @Param("attemptId") String attemptId,
      @Param("ordinal") int ordinal,
      @Param("request") String request,
      @Param("digest") String digest,
      @Param("modelVersion") String modelVersion);

  int bindDirectoryProjection(
      @Param("query") SessionQueryContext query,
      @Param("revision") long revision,
      @Param("cwd") String cwd,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId);

  List<Map<String, Object>> selectLatestStepId(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectModelStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId,
      @Param("taskId") String taskId);

  int markModelRunning(
      @Param("query") SessionQueryContext query,
      @Param("dispatchId") String dispatchId,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId);

  int rejectModelStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId);

  int storeModelResponse(
      @Param("query") SessionQueryContext query,
      @Param("response") String response,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId);

  List<Map<String, Object>> selectInvocationStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("stepId") String stepId);
}
