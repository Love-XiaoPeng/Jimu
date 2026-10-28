package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageTraceEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新T48原槽的锁和终态CAS；不会复用T46事件追溯表。 */
@Mapper
public interface SessionLineageTraceMapper extends BaseMapper<SessionLineageTraceEntity> {
  List<Map<String, Object>> lockBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int commit(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id,
      @Param("commit") SessionScopeQueries.Commit commit,
      @Param("attempt") String attempt,
      @Param("fence") long fence);
}
