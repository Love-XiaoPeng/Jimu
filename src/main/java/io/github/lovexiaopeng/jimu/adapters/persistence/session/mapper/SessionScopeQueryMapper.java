package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeQueries.Commit;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原锁／条件写和账本，沿用完整绑定键。 */
@Mapper
public interface SessionScopeQueryMapper {
  List<Map<String, Object>> lockBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int consumeTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int consumeTenant(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int settle(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int finishInvocation(
      @Param("query") SessionQueryContext query,
      @Param("outcome") String outcome,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int commitQuery(
      @Param("query") SessionQueryContext query,
      @Param("commit") Commit commit,
      @Param("attempt") String attempt,
      @Param("fence") long fence,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
