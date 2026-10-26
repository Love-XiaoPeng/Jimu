package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionContinuationMapper {
  int continuePhase(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("question") String question,
      @Param("revision") long revision);
}
