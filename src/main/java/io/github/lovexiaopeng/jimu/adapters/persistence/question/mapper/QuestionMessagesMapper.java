package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionMessagesMapper {
  List<Map<String, Object>> pending(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);
}
