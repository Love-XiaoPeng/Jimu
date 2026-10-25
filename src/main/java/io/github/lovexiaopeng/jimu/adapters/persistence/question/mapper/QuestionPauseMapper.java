package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionPauseMapper {
  int pauseAttempt(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("attempt") String attempt,
      @Param("fence") long fence);

  int pauseTask(
      @Param("query") SessionQueryContext query,
      @Param("state") String state,
      @Param("continuation") String continuation,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("fence") long fence);
}
