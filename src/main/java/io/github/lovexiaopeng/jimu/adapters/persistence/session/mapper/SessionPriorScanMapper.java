package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原PRIMARY索引有界扫描，完整租户/来源键及读取前正文长度过滤。 */
@Mapper
public interface SessionPriorScanMapper {
  List<Map<String, Object>> scan(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("session") String session,
      @Param("upper") long upper);
}
