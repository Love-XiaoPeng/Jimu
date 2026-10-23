package io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** B2 固定参数持久操作；事务、锁调用顺序及行数判定由原用例持有。 */
@Mapper
public interface ReservationMapper {
  List<Map<String, Object>> selectReservedSlot(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int releaseQueueSlot(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int reserveReleasedRun(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int insertReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId,
      @Param("taskId") String taskId,
      @Param("dispatchId") String dispatchId,
      @Param("category") String category,
      @Param("amount") long amount,
      @Param("state") String state,
      @Param("quality") String quality);

  int settleReservedAmount(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);
}
