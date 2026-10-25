package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Existing shared installation lock, retaining SELECT * and first-row behavior. */
@Mapper
public interface SessionEligibilityMapper {
  List<Map<String, Object>> lockInstallation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("plugin") String plugin);
}
