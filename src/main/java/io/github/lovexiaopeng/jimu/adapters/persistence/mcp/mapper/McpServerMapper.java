package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpServerMapper {
  List<Map<String, Object>> selectEnabledMcpServers(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  List<Map<String, Object>> selectMcpServer(
      @Param("query") SessionQueryContext query,
      @Param("tenant") Object tenant,
      @Param("server") String server);
}
