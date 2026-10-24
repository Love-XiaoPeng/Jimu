package io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.ExecutionScopeEntity;
import org.apache.ibatis.annotations.Mapper;

/** 运维scope普通读取复用MP，不允许by-id。 */
@Mapper
public interface ExecutionScopeMapper extends BaseMapper<ExecutionScopeEntity> {}
