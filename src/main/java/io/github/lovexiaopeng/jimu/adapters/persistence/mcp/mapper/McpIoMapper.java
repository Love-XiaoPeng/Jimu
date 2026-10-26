package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpIoMapper {
  List<Integer> countMcpIo(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("kind") String kind);

  List<Integer> countMcpCleanup(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  List<Integer> nextMcpOrdinal(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  int insertMcpIo(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation,
      @Param("ordinal") int ordinal,
      @Param("requestId") String requestId,
      @Param("method") String method,
      @Param("kind") String kind,
      @Param("digest") String digest);

  int respondMcpIo(
      @Param("query") SessionQueryContext query,
      @Param("phase") String phase,
      @Param("error") String error,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation,
      @Param("ordinal") int ordinal);
}
