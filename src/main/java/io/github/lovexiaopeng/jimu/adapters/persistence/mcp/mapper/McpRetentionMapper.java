package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpRetentionMapper {
  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> unknownHolds(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> lockExpiredHolds(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("updatedAt") Object updatedAt);

  int purgeResult(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("root") String root);
}
