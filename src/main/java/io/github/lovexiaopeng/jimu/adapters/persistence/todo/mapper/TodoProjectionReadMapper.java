package io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoProjectionReadEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TodoProjectionReadMapper extends BaseMapper<TodoProjectionReadEntity> {
  int advanceTodoRevision(
      @Param("query") SessionQueryContext query,
      @Param("invocation") String invocation,
      @Param("step") String step,
      @Param("attempt") String attempt,
      @Param("fence") long fence,
      @Param("seq") long seq,
      @Param("pending") int pending,
      @Param("inProgress") int inProgress,
      @Param("completed") int completed,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("expected") long expected);

  List<Map<String, Object>> todoOwnerView(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("actor") String actor);
}
