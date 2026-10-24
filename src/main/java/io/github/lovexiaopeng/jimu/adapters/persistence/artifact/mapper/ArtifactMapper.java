package io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface ArtifactMapper {
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
