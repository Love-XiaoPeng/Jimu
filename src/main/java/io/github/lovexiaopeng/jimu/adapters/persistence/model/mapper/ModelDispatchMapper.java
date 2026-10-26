package io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 模型调用的固定参数账本语句；调用者持有同一事务和全键上下文。 */
@Mapper
public interface ModelDispatchMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<
        io.github.lovexiaopeng.jimu.adapters.persistence.model.entity.ModelDispatchEntity> {
  List<Map<String, Object>> selectTaskRejection(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectRejection(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  int recordRejection(
      @Param("query") SessionQueryContext query,
      @Param("code") String code,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  int rejectOversizedResponse(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  List<Map<String, Object>> selectOutcome(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  int failOriginalDispatch(
      @Param("query") SessionQueryContext query,
      @Param("code") String code,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectEvaluationUsage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  List<Map<String, Object>> selectActiveEvaluationDispatch(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  List<Map<String, Object>> selectSettlementProof(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId,
      @Param("taskId") String taskId);

  String selectUsageSnapshotScalar(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  List<Map<String, Object>> selectReservedCost(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  int recordActualUsage(
      @Param("query") SessionQueryContext query,
      @Param("state") String state,
      @Param("code") String code,
      @Param("input") Long input,
      @Param("output") Long output,
      @Param("total") Long total,
      @Param("usage") String usage,
      @Param("actual") Long actual,
      @Param("quality") String quality,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  List<Map<String, Object>> imageCompositionDispatch(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("dispatch") String dispatch);
}
