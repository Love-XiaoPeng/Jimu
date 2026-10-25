package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionToolResultMapper {
  int completeInvocation(
      @Param("query") SessionQueryContext query,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  int settleReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
