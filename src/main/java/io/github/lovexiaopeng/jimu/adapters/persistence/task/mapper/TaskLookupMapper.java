package io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity;
import org.apache.ibatis.annotations.Mapper;

/** 普通受理查询复用MP；实际调用固定字段与全键 wrapper。 */
@Mapper
public interface TaskLookupMapper extends BaseMapper<TaskLookupEntity> {}
