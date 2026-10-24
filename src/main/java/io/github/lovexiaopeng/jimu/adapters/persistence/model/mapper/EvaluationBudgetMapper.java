package io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface EvaluationBudgetMapper {
  int stopTaskRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectRunForUpdate(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  int stopRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  int reserveModelCost(
      @Param("query") SessionQueryContext query,
      @Param("cost") long cost,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  int adjustSettledModelCost(
      @Param("query") SessionQueryContext query,
      @Param("delta") long delta,
      @Param("stop") boolean stop,
      @Param("tenant") String tenant,
      @Param("runId") String runId);
}
