package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionMaintenanceMapper {
  List<Map<String, Object>> candidates(
      @Param("query") SessionQueryContext query, @Param("enabled") boolean enabled);

  List<Map<String, Object>> due(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);
}
