package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Atomic INSERT SELECT and conditional arithmetic; no pre-read or additional transaction. */
@Mapper
public interface SessionHistoryStorageMapper {
  int create(
      @Param("query") SessionQueryContext query,
      @Param("version") int version,
      @Param("binding") String binding,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int body(
      @Param("query") SessionQueryContext query,
      @Param("additional") long additional,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
