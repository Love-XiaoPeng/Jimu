package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpResultMapper {
  int insertMcpResult(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation,
      @Param("server") String server,
      @Param("method") String method,
      @Param("binding") String binding,
      @Param("projection") String projection,
      @Param("body") String body,
      @Param("digest") String digest,
      @Param("size") long size);
}
