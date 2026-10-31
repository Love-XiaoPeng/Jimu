package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionLineageEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通MP插入和完整租户键查询，显式NULL清理由具名XML实现。 */
@Mapper
public interface SessionLineageMapper extends BaseMapper<SessionLineageEntity> {
  List<Map<String, Object>> fact(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);

  List<Map<String, Object>> nodeInputSizes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);

  List<Map<String, Object>> nodeInput(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purge(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);
}
