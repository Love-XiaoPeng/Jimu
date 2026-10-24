package io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface TenantBudgetMapper {
  List<Map<String, Object>> selectClaimTenants(@Param("query") SessionQueryContext query);

  List<Map<String, Object>> selectBudgetForUpdate(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int moveQueuedToRunning(
      @Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int incrementRunning(@Param("query") SessionQueryContext query, @Param("tenant") String tenant);

  int holdMediaCheckpoint(
      @Param("query") SessionQueryContext query,
      @Param("bytes") long bytes,
      @Param("tenant") String tenant);

  int adjustModelCost(
      @Param("query") SessionQueryContext query,
      @Param("cost") long cost,
      @Param("tenant") String tenant);

  int reserveModelTokens(
      @Param("query") SessionQueryContext query,
      @Param("tokens") long tokens,
      @Param("tenant") String tenant);

  int releaseModelTokens(
      @Param("query") SessionQueryContext query,
      @Param("tokens") long tokens,
      @Param("tenant") String tenant);
}
