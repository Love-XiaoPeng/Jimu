package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryStepEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScopeQueryStepReadMapper extends BaseMapper<ScopeQueryStepEntity> {
  int bindPrepared(
      @org.apache.ibatis.annotations.Param("query")
          io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      @org.apache.ibatis.annotations.Param("tenant") String tenant,
      @org.apache.ibatis.annotations.Param("taskId") String task,
      @org.apache.ibatis.annotations.Param("stepId") String step,
      @org.apache.ibatis.annotations.Param("upper") long upper,
      @org.apache.ibatis.annotations.Param("contextDigest") String contextDigest,
      @org.apache.ibatis.annotations.Param("bindingDigest") String bindingDigest);
}
