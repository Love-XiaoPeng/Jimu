package io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.execution.entity.TaskAttemptReadEntity;
import org.apache.ibatis.annotations.Mapper;

/** Shared attempt read metadata; callers explicitly select their original columns and full keys. */
@Mapper
public interface TaskAttemptReadMapper extends BaseMapper<TaskAttemptReadEntity> {}
