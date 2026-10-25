package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** DB microsecond clock and original non-CAS cursor increment, under the caller's history lock. */
@Mapper
public interface SessionEventAppendMapper {
  LocalDateTime clock(@Param("query") SessionQueryContext query);

  int advance(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
