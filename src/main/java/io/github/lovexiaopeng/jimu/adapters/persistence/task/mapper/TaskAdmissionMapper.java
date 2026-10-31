package io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 受理的锁、数据库时钟与原子预留；所有变更均限定 tenant 和完整键。 */
@Mapper
public interface TaskAdmissionMapper {
  List<Map<String, Object>> lockActor(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("actor") String actor);

  List<Map<String, Object>> lockInstallation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("plugin") String plugin);

  List<Map<String, Object>> selectScopeTargets(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> lockIdempotency(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("actor") String actor,
      @Param("key") String key);

  /** 仅已持锁v5受理的event后View；动态字段重新读取，授权仍消费原tools/MCP列。 */
  List<Map<String, Object>> lockAcceptedView(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  /** 同锁实际文本长度，先预留driver/Map及尾部借用owner，再物化原文。 */
  List<Map<String, Object>> lockAcceptedSizes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  /** accepted-user事实的十一列闭包；不得交给View/MCP或工具核权。 */
  List<Map<String, Object>> lockAcceptedEvent(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  /** accepted-event仅物化goal/capabilities，原文分配前读取同锁实际UTF8长度。 */
  List<Map<String, Object>> lockAcceptedEventSizes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  /** 第二次createLineage核权专用；完整Caller和末端tools/pin，不替代View/MCP核权。 */
  List<Map<String, Object>> lockLineageScope(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  List<Map<String, Object>> lockLineageScopeSizes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  long maxPacket(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> sampleBound(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run,
      @Param("model") String model,
      @Param("sample") String sample);

  int insertAccepted(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("actor") String actor,
      @Param("key") String key,
      @Param("digest") String digest,
      @Param("snapshot") String snapshot,
      @Param("goal") String goal,
      @Param("capabilities") String capabilities,
      @Param("epoch") long epoch,
      @Param("deadline") int deadline,
      @Param("models") int models,
      @Param("tools") int tools);

  int bindSnapshots(
      @Param("query") SessionQueryContext query,
      @Param("modelSnapshot") String modelSnapshot,
      @Param("modelDigest") String modelDigest,
      @Param("run") String run,
      @Param("cost") long cost,
      @Param("toolsSnapshot") String toolsSnapshot,
      @Param("toolsDigest") String toolsDigest,
      @Param("scope") String scope,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdHistory(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int insertDirectory(
      @Param("query") SessionQueryContext query,
      @Param("binding") String binding,
      @Param("digest") String digest,
      @Param("namespace") String namespace,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdDirectory(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int insertTodo(
      @Param("query") SessionQueryContext query,
      @Param("binding") String binding,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdTodo(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int bindQuestion(
      @Param("query") SessionQueryContext query,
      @Param("binding") String binding,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdQuestion(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int bindMcp(
      @Param("query") SessionQueryContext query,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int bindPolicy(
      @Param("query") SessionQueryContext query,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("tokens") long tokens,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdImages(
      @Param("query") SessionQueryContext query,
      @Param("amount") long amount,
      @Param("tenant") String tenant);

  int settleImages(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservation") String reservation);

  int bindEvaluation(
      @Param("query") SessionQueryContext query,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("phase") String phase,
      @Param("sample") String sample,
      @Param("model") String model,
      @Param("maxModels") int maxModels,
      @Param("tokens") long tokens,
      @Param("cost") long cost,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int bindMaterial(
      @Param("query") SessionQueryContext query,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("systemDigest") String systemDigest,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int setTaskCost(
      @Param("query") SessionQueryContext query,
      @Param("cost") long cost,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int holdQueue(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);
}
