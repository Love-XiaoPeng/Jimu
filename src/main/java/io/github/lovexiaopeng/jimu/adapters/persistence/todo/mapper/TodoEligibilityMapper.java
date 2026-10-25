package io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 既有完整安装行共享锁，包含禁用行并保留首行合同。 */
@Mapper
public interface TodoEligibilityMapper {
  List<Map<String, Object>> lockInstallation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("plugin") String plugin);
}
