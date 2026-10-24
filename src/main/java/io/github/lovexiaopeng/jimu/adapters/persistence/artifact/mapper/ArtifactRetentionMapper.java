package io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArtifactRetentionMapper {
  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> lockArtifact(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifactId") String artifactId);

  int mark(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("artifactId") String artifactId,
      @Param("state") String state);

  int releaseStorage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("amount") long amount);
}
