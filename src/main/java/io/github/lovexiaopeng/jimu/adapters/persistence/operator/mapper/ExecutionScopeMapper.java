package io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.ExecutionScopeEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 运维scope普通读取复用MP，不允许by-id。 */
@Mapper
public interface ExecutionScopeMapper extends BaseMapper<ExecutionScopeEntity> {
  List<Map<String, Object>> consumptionReceipts(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  List<String> boundRuns(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  List<Map<String, Object>> consumptionScopes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);
}
