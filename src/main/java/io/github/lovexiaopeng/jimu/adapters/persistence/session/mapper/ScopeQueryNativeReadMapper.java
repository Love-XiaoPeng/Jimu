package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryNativeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScopeQueryNativeReadMapper extends BaseMapper<ScopeQueryNativeEntity> {}
