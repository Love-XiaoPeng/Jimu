package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;
import java.util.function.Supplier;

/** 调度/失效后记的显式短事务端口，同DS并保留SessionQueryTransaction最后截止检查。 */
public interface SessionScopeMaintenanceRecords {
  record Candidate(String tenant, String caller) {}

  final class StorageFailure extends RuntimeException {
    public StorageFailure(RuntimeException cause) {
      super(cause);
    }
  }

  interface Locked {
    void defer();

    void account();

    void requireCurrent();

    void invalidate();

    boolean invalidated();

    boolean historyPresent();

    boolean cleanupSafe();

    void purge();

    void invalidateAndSchedule();
  }

  <T> T required(long end, Supplier<T> work);

  <T> T requiresNew(Supplier<T> work);

  List<Candidate> candidates();

  Locked lock(Candidate candidate);

  void remaining();

  void deferred(boolean scan, String reason);
}
