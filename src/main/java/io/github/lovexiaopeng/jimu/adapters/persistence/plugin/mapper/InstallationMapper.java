package io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 全tenant/plugin键的MP查询；不提供复合键by-id。 */
@Mapper
public interface InstallationMapper extends BaseMapper<InstallationEntity> {}
