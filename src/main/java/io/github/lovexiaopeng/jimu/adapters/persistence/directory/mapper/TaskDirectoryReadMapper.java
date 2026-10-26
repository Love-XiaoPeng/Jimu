package io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity.TaskDirectoryReadEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskDirectoryReadMapper extends BaseMapper<TaskDirectoryReadEntity> {
  int readyWorkspace(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int advanceDirectoryRevision(
      @Param("query") SessionQueryContext query,
      @Param("cwd") String cwd,
      @Param("revision") long revision,
      @Param("invocation") String invocation,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("base") long base);

  List<Map<String, Object>> directoryOwnerView(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("actor") String actor);

  java.util.List<java.util.Map<String, Object>> metadataRows(
      @org.apache.ibatis.annotations.Param("query")
          io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      @org.apache.ibatis.annotations.Param("tenant") String tenant,
      @org.apache.ibatis.annotations.Param("task") String task);
}
