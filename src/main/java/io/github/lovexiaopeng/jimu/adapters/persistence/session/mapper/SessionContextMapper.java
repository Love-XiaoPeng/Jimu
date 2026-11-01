package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原metadata固定点的读取和精确账目更新；同租户完整键。 */
@Mapper
public interface SessionContextMapper {
  List<Map<String, Object>> metadataBytes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> balance(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int account(
      @Param("query") SessionQueryContext query,
      @Param("bytes") long bytes,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
