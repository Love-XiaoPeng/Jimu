package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.time.LocalDateTime;

/** 公共历史证明的具名事实；JSON、Map及显式query只留在适配边界。 */
public interface SessionEventProofs {
  record Subject(
      String tenant,
      String task,
      long version,
      boolean scoped,
      boolean trace,
      String requestDigest) {}

  record Stored(
      String tenant,
      String task,
      long recordVersion,
      long eventVersion,
      String content,
      String raw,
      long bytes,
      String digest,
      long seq,
      String type,
      LocalDateTime time,
      String step,
      String invocation,
      String fact,
      String sourceDigest,
      long reliable,
      String attempt,
      Long fence) {}

  record Document(
      boolean integralVersion,
      long version,
      String type,
      String session,
      long seq,
      String time,
      String step,
      String invocation,
      String sourceKind,
      long ordinal,
      String state,
      String code,
      String data,
      boolean sourcesEmpty,
      boolean refsEmpty) {}

  record Step(
      String id,
      long ordinal,
      String request,
      String requestDigest,
      String response,
      String responseDigest) {}

  record Invocation(
      String id, String state, String inputDigest, String output, String outputDigest) {}

  record Read(Long receipt, String outcome, String outputDigest, Long outputBytes) {}

  record Terminal(String status, String code, String digest) {}

  interface Proof {
    Subject subject();

    Stored stored();

    void operation();

    Document decode();

    void validateDocument();

    String hash(String raw);

    int bytes(String raw);

    boolean sourceMatches(String kind);

    Step step();

    Invocation invocation();

    Read read();

    String readReceipt();

    String scopeRead();

    void readRelation();

    Terminal terminal();

    String reliableType(long seq);

    void requireAttempt(String attempt, long fence);
  }
}
