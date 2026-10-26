package io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.DirectoryPathReadEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DirectoryPathReadMapper extends BaseMapper<DirectoryPathReadEntity> {
  List<Map<String, Object>> existingDirectoryBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int insertDirectoryBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation,
      @Param("task") String task,
      @Param("step") String step,
      @Param("binding") String binding,
      @Param("namespace") String namespace,
      @Param("slot") long slot,
      @Param("mode") String mode,
      @Param("revision") long revision,
      @Param("cwd") String cwd,
      @Param("target") String target,
      @Param("paths") String paths,
      @Param("digest") String digest,
      @Param("input") String input);

  List<Map<String, Object>> frozenDirectoryBinding(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int completeDirectoryBinding(
      @Param("query") SessionQueryContext query,
      @Param("revision") long revision,
      @Param("changed") boolean changed,
      @Param("attempt") String attempt,
      @Param("fence") long fence,
      @Param("seq") Long seq,
      @Param("proof") String proof,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
