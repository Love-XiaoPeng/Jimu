package io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DirectoryRetentionMapper {
  List<Map<String, Object>> unsafeReservations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> activeInvocations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> dispatchedModels(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  int purgeStep(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purgeInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purgePath(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purgeDispatch(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purgeTask(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markPurging(
      @Param("query") SessionQueryContext query,
      @Param("retained") String retained,
      @Param("generation") String generation,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int storeReceipt(
      @Param("query") SessionQueryContext query,
      @Param("receipt") String receipt,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int markPurged(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int releaseHold(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("holdId") String holdId);

  int releaseBudget(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  java.lang.Long storageBytes(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  java.time.LocalDateTime now(@Param("query") SessionQueryContext query);
}
