package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原单列Long汇总；List保留空行、SQL NULL及基数，由适配器沿用Spring合同。 */
@Mapper
public interface QuestionStorageMapper {
  List<Long> bytes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);
}
