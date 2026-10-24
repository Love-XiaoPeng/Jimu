package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskVisibilityEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskVisibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;

/** 固定MP可见性查询；显式query同级传入，旧失效分支仅取原来的三列。 */
final class MapperTaskVisibility {
  private final SqlSessionTemplate session;

  MapperTaskVisibility(SqlSessionTemplate session) {
    this.session = session;
  }

  List<Map<String, Object>> select(
      SessionQueryContext query, Tasks.Identity identity, String task, boolean hint) {
    var wrapper = new LambdaQueryWrapper<TaskVisibilityEntity>();
    if (hint)
      wrapper.select(
          TaskVisibilityEntity::getTenantId,
          TaskVisibilityEntity::getTaskId,
          TaskVisibilityEntity::getCapabilities);
    wrapper
        .eq(TaskVisibilityEntity::getTenantId, identity.tenant())
        .eq(TaskVisibilityEntity::getTaskId, task)
        .eq(TaskVisibilityEntity::getOwnerActorId, identity.actor());
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", query);
    return session.selectList(TaskVisibilityMapper.class.getName() + ".selectMaps", parameters);
  }
}
