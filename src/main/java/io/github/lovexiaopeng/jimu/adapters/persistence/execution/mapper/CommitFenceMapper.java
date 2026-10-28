package io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommitFenceMapper {
  int commitRetainedFence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("attempt") String attempt,
      @Param("worker") String worker,
      @Param("fence") long fence,
      @Param("retain") java.time.LocalDateTime retain);

  int commitFence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("attempt") String attempt,
      @Param("worker") String worker,
      @Param("fence") long fence);
}
