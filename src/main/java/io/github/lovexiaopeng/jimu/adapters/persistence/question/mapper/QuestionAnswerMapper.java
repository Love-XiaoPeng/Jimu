package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionAnswerMapper {
  List<Map<String, Object>> lockQuestion(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("request") String request);

  List<Map<String, Object>> previous(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("actor") String actor,
      @Param("key") String key);

  List<Map<String, Object>> reload(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("request") String request);

  List<Integer> sequence(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int answer(
      @Param("query") SessionQueryContext query,
      @Param("answerId") String answerId,
      @Param("actor") String actor,
      @Param("answeredAt") Timestamp answeredAt,
      @Param("seq") int seq,
      @Param("answer") String answer,
      @Param("answerDigest") String answerDigest,
      @Param("key") String key,
      @Param("requestDigest") String requestDigest,
      @Param("sourceRevision") int sourceRevision,
      @Param("receipt") String receipt,
      @Param("delivery") String delivery,
      @Param("tenant") String tenant,
      @Param("request") String request,
      @Param("revision") long revision);
}
