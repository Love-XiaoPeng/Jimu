package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原PRIMARY索引有界端点扫描，保持tenant/目标完整键及读取前正文条件。 */
@Mapper
public interface SessionScopeReadProofMapper {
  List<Map<String, Object>> endpoints(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("session") String session,
      @Param("from") long from,
      @Param("to") long to);
}
