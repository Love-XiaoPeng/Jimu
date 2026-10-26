package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeMaintenanceRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeMaintenanceRecords.Candidate;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeMaintenanceRecords.StorageFailure;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeMaintenanceService;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.util.List;

/** 先独立提交下一调度，再当前资格验证；超时不能使坏源饿死后续候选。 */
public final class SessionScopeMaintenanceServiceImpl implements SessionScopeMaintenanceService {
  private final SessionScopeMaintenanceRecords records;

  public SessionScopeMaintenanceServiceImpl(SessionScopeMaintenanceRecords records) {
    this.records = records;
  }

  public void validate() {
    long end = System.nanoTime() + SessionHistoryPolicyV1.QUERY_NANOS;
    try {
      var candidates = records.required(end, records::candidates);
      for (var candidate : candidates) {
        if (System.nanoTime() >= end) break;
        try {
          records.required(
              end,
              () -> {
                var task = records.lock(candidate);
                task.defer();
                task.account();
                return null;
              });
          records.required(
              end,
              () -> {
                var task = records.lock(candidate);
                try {
                  task.requireCurrent();
                } catch (TaskFailure failure) {
                  if (!List.of(
                          SessionErrorCode.SESSION_SCOPE_UNAVAILABLE,
                          CommonErrorCode.CAPABILITY_REVOKED,
                          CommonErrorCode.ACTOR_REVOKED,
                          SessionErrorCode.SESSION_HISTORY_EXPIRED)
                      .contains(failure.code())) throw failure;
                  task.invalidate();
                }
                if (task.invalidated() && task.historyPresent() && task.cleanupSafe()) task.purge();
                else task.account();
                return null;
              });
        } catch (StorageFailure failure) {
          records.deferred(false, failure.getCause().getClass().getSimpleName());
        } catch (TaskFailure failure) {
          records.deferred(false, failure.code());
        }
      }
    } catch (StorageFailure failure) {
      records.deferred(true, failure.getCause().getClass().getSimpleName());
    } catch (TaskFailure failure) {
      if (!failure.code().equals(SessionErrorCode.SESSION_QUERY_TIMEOUT)) throw failure;
    }
  }

  public void persistInvalidation(String tenant, String caller) {
    records.requiresNew(
        () -> {
          var task = records.lock(new Candidate(tenant, caller));
          task.invalidateAndSchedule();
          task.account();
          records.remaining();
          return null;
        });
  }
}
