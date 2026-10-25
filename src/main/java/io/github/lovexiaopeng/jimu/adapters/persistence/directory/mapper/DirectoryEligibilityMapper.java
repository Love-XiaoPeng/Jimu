package io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Original full-row installation shared lock, including disabled rows and first-row semantics. */
@Mapper
public interface DirectoryEligibilityMapper {
  List<Map<String, Object>> lockInstallation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("plugin") String plugin);
}
