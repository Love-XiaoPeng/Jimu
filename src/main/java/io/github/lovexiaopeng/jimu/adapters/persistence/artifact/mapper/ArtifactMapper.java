package io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface ArtifactMapper {
  List<Map<String, Object>> selectTaskArtifacts(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  List<Map<String, Object>> selectLastSucceededInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int insertStaging(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifact") String artifact,
      @Param("task") String task,
      @Param("producer") String producer,
      @Param("upload") String upload,
      @Param("fence") long fence,
      @Param("hash") String hash,
      @Param("size") long size);

  int holdStorage(
      @Param("query") SessionQueryContext query,
      @Param("size") long size,
      @Param("tenant") String tenant);

  int markVerified(
      @Param("query") SessionQueryContext query,
      @Param("key") String key,
      @Param("fence") long fence,
      @Param("tenant") String tenant,
      @Param("artifact") String artifact,
      @Param("task") String task);

  List<Map<String, Object>> selectTaskArtifact(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifact") String artifact,
      @Param("task") String task);

  int markPublished(
      @Param("query") SessionQueryContext query,
      @Param("fence") long fence,
      @Param("tenant") String tenant,
      @Param("artifact") String artifact);

  int settleStorage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifact") String artifact);

  List<Map<String, Object>> selectOwnedRetained(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifactId") String artifactId,
      @Param("actor") String actor);

  List<Map<String, Object>> selectPublishedReport(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> selectUnpublishedArtifacts(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
