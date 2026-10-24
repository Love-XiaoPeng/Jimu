package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeSourceReadEntity;
import org.apache.ibatis.annotations.Mapper;

/** 普通来源读取真实复用MP；不使用by-id。 */
@Mapper
public interface ScopeSourceReadMapper extends BaseMapper<ScopeSourceReadEntity> {}
