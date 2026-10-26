package io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 固定全键事实查询；事务由调用者持有。 */
@Mapper
public interface ImageObjectMapper {
  List<Map<String, Object>> selectCommittedProducer(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId,
      @Param("invocationId") String invocationId);

  List<Map<String, Object>> selectProducerImages(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);

  int reserveImageObject(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("image") String image,
      @Param("invocation") String invocation,
      @Param("inputId") String inputId,
      @Param("normalizer") String normalizer);

  int commitImageObject(
      @Param("query") SessionQueryContext query,
      @Param("key") String key,
      @Param("sha256") String sha256,
      @Param("size") long size,
      @Param("width") int width,
      @Param("height") int height,
      @Param("originalWidth") int originalWidth,
      @Param("originalHeight") int originalHeight,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("image") String image,
      @Param("invocation") String invocation,
      @Param("inputId") String inputId);

  List<Map<String, Object>> selectRetainedImageObject(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("image") String image);
}
