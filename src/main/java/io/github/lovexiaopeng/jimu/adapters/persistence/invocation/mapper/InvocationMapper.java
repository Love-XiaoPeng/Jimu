package io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface InvocationMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<
        io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity.InvocationEntity> {
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

  long countTaskInvocationsScalar(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int countAttemptInvocationsScalar(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attemptId") String attemptId);

  int bindTodoRevision(
      @Param("query") SessionQueryContext query,
      @Param("revision") long revision,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("stepId") String stepId,
      @Param("slot") String slot);

  List<Map<String, Object>> selectOriginalSlot(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId,
      @Param("slot") String slot,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectSuccessfulRead(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markAdmittedRunning(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId);

  List<Map<String, Object>> selectTaskInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId);

  int succeedRunning(
      @Param("query") SessionQueryContext query,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId,
      @Param("taskId") String taskId);

  int failRunning(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId,
      @Param("taskId") String taskId);

  int markInvocationUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocationId") String invocationId,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectMaterialSources(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectMaterialFileHistory(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectProtectedInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId,
      @Param("tool") String tool);

  List<Map<String, Object>> selectWebDiagnosticBindings(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId,
      @Param("attemptId") String attemptId,
      @Param("worker") String worker,
      @Param("fence") long fence);

  int preserveWebResult(
      @Param("query") SessionQueryContext query,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId);

  List<Map<String, Object>> selectRunningImageInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectSucceededImageInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectStepImageOutputs(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("step") String step);

  List<Map<String, Object>> selectMcpInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectMcpListInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectMcpProducer(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Map<String, Object>> selectSearchInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("task") String task);
}
