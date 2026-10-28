package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** 会话保留清理专用事实；不向内核暴露数据库行或JSON节点。 */
public interface SessionHistoryRetentions {
  record Candidate(String tenant, String taskId) {}

  interface CandidateBatch extends Iterable<Candidate>, AutoCloseable {
    @Override
    void close();
  }

  default CandidateBatch candidateBatch() {
    return new CandidateBatch() {
      private List<Candidate> values = candidates();

      public java.util.Iterator<Candidate> iterator() {
        if (values == null) throw new IllegalStateException("Closed retention candidates");
        return values.iterator();
      }

      public void close() {
        values = null;
      }
    };
  }

  record History(
      long recordVersion,
      long version,
      String binding,
      String digest,
      String state,
      boolean retained,
      long bodyBytes) {}

  record Body(String field, String value) {}

  record ContentRow(Object identity, List<Body> bodies) {}

  record ContentTable(String name, List<ContentRow> rows) {}

  record RetainedBody(String field, String sha256, long bytes) {}

  record RetainedRow(Object identity, List<RetainedBody> bodies) {}

  record RetainedTable(String name, List<RetainedRow> rows) {}

  record MetadataRow(List<String> scalars) {}

  /** 已purge同一锁内快照，扣除metadata/held/actual三个十进制值，state已影子置SETTLED。 */
  record PurgeMetadata(long fixedBytes) {}

  interface LockedHistory {
    History history();

    int expectedVersion();

    String expectedBinding();

    boolean scoped();

    boolean searchable();

    boolean invalidated();

    String state();

    boolean writersQuiescent();

    boolean activeInvocations();

    boolean unsafeReservations();

    boolean dispatchedModels();

    List<ContentTable> contents();

    void purgeScopeQuery();

    void purgeScopeStep();

    void purgeScopeTargets();

    void purgeScope();

    default void purgeLineage() {
      throw new UnsupportedOperationException("v5 retention required");
    }

    void purgePublicEvents();

    void purgeSearch();

    void purgeTrace();

    void purgeRead();

    void purgeInvocations();

    void purgeSteps();

    void purgeDispatches();

    void purgeTask();

    void markPurged(String receipt);

    void event();

    List<MetadataRow> metadata();

    default PurgeMetadata purgeMetadata() {
      return null;
    }

    default void operation(long count) {}

    /** 已按原32/8/UTF8公式消费并释放的lineage固定投影；其余表仍由metadata提供。 */
    default long lineageMetadataBytes() {
      return 0;
    }

    void settleHistory(long bytes);

    void settleReservation(long bytes);

    void releaseBudget(long bytes);
  }

  List<Candidate> candidates();

  void lockBudget(String tenant);

  LockedHistory lockTask(Candidate candidate);

  String receipt(List<RetainedTable> tables, long bodyBytes, String bindingDigest);
}
