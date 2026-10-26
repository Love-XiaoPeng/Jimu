package io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionOpeningMapper {
  List<Map<String, Object>> invocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("step") String step,
      @Param("slot") String slot);

  List<Integer> ordinal(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int insert(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("request") String request,
      @Param("task") String task,
      @Param("actor") String actor,
      @Param("invocation") String invocation,
      @Param("ordinal") int ordinal,
      @Param("binding") String binding,
      @Param("mode") String mode,
      @Param("timeout") Integer timeout,
      @Param("raw") String raw,
      @Param("digest") String digest,
      @Param("dueNull") Integer dueNull,
      @Param("dueUnlimited") Integer dueUnlimited,
      @Param("dueSeconds") Integer dueSeconds,
      @Param("deadline") Object deadline,
      @Param("retain") Timestamp retain);

  int waiting(
      @Param("query") SessionQueryContext query,
      @Param("request") String request,
      @Param("tenant") String tenant,
      @Param("task") String task);

  int budget(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int waitingInvocation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("invocation") String invocation);
}
