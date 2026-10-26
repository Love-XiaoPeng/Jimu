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

  int holdPreviousDispatchUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  long selectTokenAmountScalar(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  List<Map<String, Object>> selectTokenAmount(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int settleModelTokens(
      @Param("query") SessionQueryContext query,
      @Param("actual") long actual,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int holdModelUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int holdReservedModelUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int holdFailedModelUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  List<Map<String, Object>> selectEvaluationUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("runId") String runId);

  int holdInvalidModelProof(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("dispatchId") String dispatchId);

  int settleActualModelCost(
      @Param("query") SessionQueryContext query,
      @Param("actual") long actual,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int holdCostUnknown(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int settleToolUnit(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservationId") String reservationId);

  int settleImageReservation(
      @Param("query") SessionQueryContext query,
      @Param("actual") long actual,
      @Param("tenant") String tenant,
      @Param("image") String image);

  List<Integer> countMcpReservations(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task);

  List<Map<String, Object>> selectMcpReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("task") String task,
      @Param("reservation") String reservation);

  int settleMcpReservation(
      @Param("query") SessionQueryContext query,
      @Param("size") long size,
      @Param("tenant") String tenant,
      @Param("reservation") String reservation);

  int settleSearchStorage(
      @Param("query") SessionQueryContext query,
      @Param("size") long size,
      @Param("tenant") String tenant,
      @Param("reservation") String reservation);

  List<Map<String, Object>> selectSearchRemovalReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservation") String reservation);

  int releaseSearchReservation(
      @Param("query") SessionQueryContext query,
      @Param("tenant") String tenant,
      @Param("reservation") String reservation);
}
