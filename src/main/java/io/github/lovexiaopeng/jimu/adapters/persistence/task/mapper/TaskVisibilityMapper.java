package io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskVisibilityEntity;
import org.apache.ibatis.annotations.Mapper;

/** 只按固定tenant/owner/task全键条件读取，禁止by-id。 */
@Mapper
public interface TaskVisibilityMapper extends BaseMapper<TaskVisibilityEntity> {}
