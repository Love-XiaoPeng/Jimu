package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionReads.Commit;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原生读取锁与条件提交，沿用tenant和invocation完整键。 */
@Mapper
public interface SessionReadMapper {
  List<Map<String, Object>> lockBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int commitRead(
      @Param("query") SessionQueryContext query,
      @Param("commit") Commit commit,
      @Param("attempt") String attempt,
      @Param("fence") long fence,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
