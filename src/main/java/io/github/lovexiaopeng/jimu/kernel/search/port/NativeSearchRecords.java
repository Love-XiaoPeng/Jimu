package io.github.lovexiaopeng.jimu.kernel.search.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import java.util.List;
import java.util.function.Supplier;

/** 原生检索专属惰性行及单项I/O；业务资格由Service持有。 */
public interface NativeSearchRecords {
  interface Task {}

  interface Budget {
    long storageHeld();

    long mediaCheckpointHeld();

    long storageLimit();
  }

  interface CallRow {
    String state();

    String tool();

    String effectIntentId();
  }

  interface Aggregate {
    long roots();

    long bytes();
  }

  interface Worker {
    String hostId();
  }

  interface RunRow {
    String tenantId();

    String taskId();

    String rootInvocation();

    String effectId();

    String attemptId();

    String workerId();

    String runtimeDirectory();

    String snapshotDigest();

    String phase();

    String processIdentity();

    Object processIdentityObject();

    Object resultHashObject();

    String cursorIndex();

    String queryDigest();

    String toolPin();

    String resultHash();

    long resultSize();

    int stopRequested();
  }

  interface Reservation {
    String state();

    long amount();

    long actual();

    Object actualObject();
  }

  interface CursorIndex {
    boolean has(String cursor);

    CursorIndex path(String cursor);

    int intValue();
  }

  <T> T leaseTransaction(Lease lease, Supplier<T> work);

  <T> T transaction(Supplier<T> work);

  void fenced(Lease lease, boolean admission);

  Budget budget(String tenant);

  Task locked(String tenant, String task);

  void settleStoppedWriters(Task task);

  void reserve(
      String tenant,
      String task,
      String reservation,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome);

  String json(Object value);

  String hash(String value);

  CursorIndex readIndex(String raw);

  NativeIdentity readIdentity(String raw);

  List<RunRow> selectNonStoppedSearch(String tenant, String task, String invocation);

  CallRow selectSearchInvocation(String tenant, String invocation, String task);

  Aggregate selectSearchAggregate(String tenant, String task);

  Worker selectSearchWorkerHost(String worker);

  int insertSearchRun(
      String tenant,
      String invocation,
      String task,
      String effect,
      String attempt,
      String worker,
      String host,
      String directory,
      String snapshot,
      String digest,
      String pin);

  int holdSearchStorage(String tenant);

  int startSearchRun(
      String identity, String tenant, String task, String invocation, String attempt);

  RunRow selectSearchStopRun(
      String tenant, String invocation, String task, String effect, String worker);

  int insertSearchStop(String tenant, String invocation, String digest, String identity);

  int stopSearchRun(String identity, String tenant, String invocation);

  RunRow selectSearchCompleteRun(String tenant, String task, String invocation, String attempt);

  int completeSearchRun(String hash, long size, String cursors, String tenant, String invocation);

  int settleSearchStorage(long size, String tenant, String reservation);

  int releaseCompletedSearchStorage(long bytes, String tenant);

  List<RunRow> selectSearchPage(String tenant, String task);

  List<RunRow> selectNativeSearches(String host);

  List<RunRow> selectExpiredSearches(String host);

  RunRow selectSearchRemovalRun(String tenant, String invocation, String task, String effect);

  Reservation selectSearchRemovalReservation(String tenant, String reservation);

  int releaseRemovedSearchStorage(long bytes, String tenant);

  int releaseSearchReservation(String tenant, String reservation);

  int removeSearchStorage(String tenant, String invocation);
}
