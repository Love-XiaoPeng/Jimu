package io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.operator.entity.OperatorStepEntity;
import org.apache.ibatis.annotations.Mapper;

/** 证明查询按完整tenant/task/对象键使用MP wrapper。 */
@Mapper
public interface OperatorStepMapper extends BaseMapper<OperatorStepEntity> {}
