package io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MediaRetentionMapper {
  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> unknownHolds(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markDeleting(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> lockCheckpoints(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int purgeCheckpoint(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("stepId") String stepId,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest);

  List<Map<String, Object>> lockHolds(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int releaseCheckpointBytes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("bytes") long bytes);

  int purgeInputs(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markObjectsDeleted(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markTaskDeleted(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);
}
