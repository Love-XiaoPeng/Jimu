package io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImageInputMapper {

  int insertInput(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("inputId") String inputId,
      @Param("logicalPath") String logicalPath,
      @Param("sha256") String sha256,
      @Param("size") long size,
      @Param("rawBase64") String rawBase64);

  List<Map<String, Object>> selectInputIds(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  List<Map<String, Object>> selectImageInput(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("inputId") String inputId);
}
