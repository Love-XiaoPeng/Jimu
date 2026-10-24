package io.github.lovexiaopeng.jimu.kernel.session.port;

/** 共享历史/step证明事实；Checkpoint和冻结context编解码停留在适配层。 */
public interface SessionHistoryProofs {
  record HistoryRow(
      Long recordVersion,
      Long version,
      String snapshot,
      String digest,
      String state,
      Long retained) {}

  interface History {
    HistoryRow load();

    long version();

    String binding();

    String hash(String raw);
  }

  record StepRow(
      Long version,
      String task,
      Long upper,
      String request,
      String requestDigest,
      String contextDigest,
      String bindingDigest) {}

  record Context(String session, long upper) {}

  interface Step {
    StepRow step();

    String task();

    String hash(String raw);

    int checkpointVersion(String raw);

    boolean messagesEmpty();

    String lastContent();

    void validateContext(String raw);

    Context context(String raw);

    String lastRole();

    String historyBinding();
  }
}
