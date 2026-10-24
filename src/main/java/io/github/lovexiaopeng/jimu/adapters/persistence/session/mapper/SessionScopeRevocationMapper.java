package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 完整tenant／owner键锁与ACTIVE条件撤销，保持原幂等更新语义。 */
@Mapper
public interface SessionScopeRevocationMapper {
  List<Map<String, Object>> lockOwner(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller,
      @Param("actor") String actor);

  int revoke(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);
}
