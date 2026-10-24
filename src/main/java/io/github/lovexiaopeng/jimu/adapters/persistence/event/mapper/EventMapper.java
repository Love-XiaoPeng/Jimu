package io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface EventMapper {
  long selectFactSequenceValue(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("factId") String factId);

  List<Map<String, Object>> selectFactSequence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("factId") String factId);

  int insertFact(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("sequence") long sequence,
      @Param("eventId") String eventId,
      @Param("factId") String factId,
      @Param("type") String type,
      @Param("code") String code,
      @Param("status") String status,
      @Param("artifactId") String artifactId);

  List<Map<String, Object>> selectFactCode(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("factId") String factId);
}
