package io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryInvocationReadEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DirectoryInvocationReadMapper extends BaseMapper<DirectoryInvocationReadEntity> {
  List<Map<String, Object>> originalDirectoryInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("step") String step,
      @Param("slot") String slot);

  List<Map<String, Object>> priorDirectorySlot(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("step") String step,
      @Param("slot") String slot);

  List<Map<String, Object>> unfinishedDirectoryStep(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("ordinal") long ordinal);

  List<Map<String, Object>> directoryCommitInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);

  int completeDirectoryInvocation(
      @Param("query") SessionQueryContext query,
      @Param("output") String output,
      @Param("digest") String digest,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  List<Map<String, Object>> directoryFileInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("invocation") String invocation);
}
