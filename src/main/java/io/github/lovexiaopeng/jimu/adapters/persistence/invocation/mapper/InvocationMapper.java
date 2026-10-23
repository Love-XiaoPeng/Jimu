package io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface InvocationMapper {
  List<Map<String, Object>> selectPendingEffects(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markRunningEffectsUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectUnfinishedStepInvocations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId);

  List<Map<String, Object>> selectUnfinishedTaskInvocations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
