package io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.todo.entity.TodoInvocationReadEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TodoInvocationReadMapper extends BaseMapper<TodoInvocationReadEntity> {
  List<Map<String, Object>> todoCandidates(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("step") String step,
      @Param("slot") String slot);

  int completeTodoInvocation(
      @Param("query") SessionQueryContext query,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("revision") long revision,
      @Param("attempt") String attempt,
      @Param("fence") long fence,
      @Param("seq") long seq,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
