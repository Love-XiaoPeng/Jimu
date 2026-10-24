package io.github.lovexiaopeng.jimu.adapters.persistence.worker.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface WorkerProcessMapper
    extends com.baomidou.mybatisplus.core.mapper.BaseMapper<
        io.github.lovexiaopeng.jimu.adapters.persistence.worker.entity.WorkerProcessEntity> {
  List<Map<String, Object>> selectObservedProcesses(
      @Param("query") SessionQueryContext query, @Param("host") String host);

  List<Map<String, Object>> selectActiveNativeWorker(
      @Param("query") SessionQueryContext query, @Param("worker") String worker);
}
