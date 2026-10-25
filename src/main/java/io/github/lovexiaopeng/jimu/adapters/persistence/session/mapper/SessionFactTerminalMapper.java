package io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionFactTerminalEntity;
import org.apache.ibatis.annotations.Mapper;

/** Full-key session fact projection; original values are read through selectMaps. */
@Mapper
public interface SessionFactTerminalMapper extends BaseMapper<SessionFactTerminalEntity> {}
