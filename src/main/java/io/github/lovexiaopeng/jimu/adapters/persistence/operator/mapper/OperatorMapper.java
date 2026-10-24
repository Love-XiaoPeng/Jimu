package io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 锁/联查/账本及外部receipt的显式参数查询；仅insert可写scope。 */
@Mapper
public interface OperatorMapper {
  List<Map<String, Object>> lockRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  List<Map<String, Object>> lockBudget(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  long dispatchCount(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  long accountedCost(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  long invalidTasks(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  long tenantCost(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  long tenantModels(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  long invalidReservations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  int insert(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run,
      @Param("snapshot") String snapshot,
      @Param("digest") String digest,
      @Param("boundBy") String boundBy);

  List<Map<String, Object>> tasks(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  List<Map<String, Object>> dispatches(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  List<Map<String, Object>> reservations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("run") String run);

  long receiptTable(@Param("query") SessionQueryContext query);

  List<String> receiptColumns(@Param("query") SessionQueryContext query);

  String serverUuid(@Param("query") SessionQueryContext query);

  String schema(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> receipts(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("versionColumn") boolean versionColumn);
}
