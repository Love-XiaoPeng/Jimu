package io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.mcp.entity.McpCursorEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface McpCursorMapper extends BaseMapper<McpCursorEntity> {
  List<Map<String, Object>> selectMcpPage(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("token") String token);

  int insertMcpCursor(
      @Param("query") SessionQueryContext query,
      @Param("token") String token,
      @Param("page") Integer page,
      @Param("queryDigest") String queryDigest,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);
}
