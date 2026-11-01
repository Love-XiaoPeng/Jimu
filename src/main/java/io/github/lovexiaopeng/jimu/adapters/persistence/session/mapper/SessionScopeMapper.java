package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 有界scope候选、完整键、原有序锁和幂等调度／失效写入。 */
@Mapper
public interface SessionScopeMapper {
  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> callerCapabilities(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> lockTargets(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> lockTask(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> scope(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> currentState(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> targets(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> hasTargets(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  List<Map<String, Object>> admissionRetention(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  int defer(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  int invalidate(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  int invalidateAndSchedule(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("caller") String caller);

  int insertAuthority(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("binding") String binding,
      @Param("tools") String tools,
      @Param("raw") String raw,
      @Param("digest") String digest,
      @Param("bytes") int bytes,
      @Param("count") int count,
      @Param("created") LocalDateTime created,
      @Param("retain") LocalDateTime retain,
      @Param("validation") LocalDateTime validation);

  int insertAuthorityTarget(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id,
      @Param("ordinal") int ordinal,
      @Param("source") Object source,
      @Param("raw") String raw,
      @Param("digest") String digest,
      @Param("bytes") int bytes);
}
