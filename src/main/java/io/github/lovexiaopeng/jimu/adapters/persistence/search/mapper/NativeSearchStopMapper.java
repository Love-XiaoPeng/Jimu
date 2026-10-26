package io.github.lovexiaopeng.jimu.adapters.persistence.search.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NativeSearchStopMapper {
  int insertSearchStop(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("digest") String digest,
      @Param("identity") String identity);
}
