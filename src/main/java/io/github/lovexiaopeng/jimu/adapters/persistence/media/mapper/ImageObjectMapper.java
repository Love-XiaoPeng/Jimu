package io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 固定全键事实查询；事务由调用者持有。 */
@Mapper
public interface ImageObjectMapper {
  List<Map<String, Object>> selectCommittedProducer(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId);
}
