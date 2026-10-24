package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionRetentionMapper {
  List<Map<String, Object>> candidates(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> unsafeReservations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  List<Map<String, Object>> unknownInvocations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

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

  int purgeQuestion(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("retained") String retained,
      @Param("tenant") String tenant,
      @Param("id") String id);

  int purgeTask(
      @Param("query") SessionQueryContext query,
      @Param("tombstone") String tombstone,
      @Param("retained") String retained,
      @Param("tenant") String tenant,
      @Param("taskId") String taskId);

  int releaseHold(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("holdId") String holdId);

  int releaseBudget(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);
}
