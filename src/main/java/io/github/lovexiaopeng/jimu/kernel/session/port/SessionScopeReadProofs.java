package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** scope读取的原槽、切点及直接端点事实；JSON和数据库值留在适配器。 */
public interface SessionScopeReadProofs {
  record Event(String fact, long seq, String sourceKind, String data, String refs) {}

  record Invocation(
      String id,
      String step,
      String tool,
      String input,
      String inputDigest,
      String state,
      String output,
      String outputDigest) {}

  record Binding(
      String operation,
      String authority,
      String context,
      String binding,
      String normalized,
      String normalizedDigest,
      long normalizedBytes,
      Long nativeSeq,
      String outcome,
      String inputDigest,
      String outputDigest,
      Long outputBytes) {}

  record Step(String context, String binding) {}

  record Cut(String authority, String context, String rawContext) {}

  record Ref(String session, long seq, String digest) {}

  record Refs(String canonical, List<Ref> values) {}

  record Normalized(String session, long seq, int before, int after, long upper) {}

  record Output(
      String session,
      long upper,
      long from,
      long to,
      String target,
      List<String> before,
      List<String> after) {}

  record Endpoint(long seq, String raw, String digest, long bytes) {}

  record Source(String session, long seq, String canonical) {}

  interface Proof {
    Event event();

    Invocation invocation();

    Binding binding();

    Step step();

    void validateStep();

    String authority();

    Cut cut();

    String normalizedInput();

    boolean sameJson(String left, String right, int limit);

    String hash(String raw);

    int bytes(String raw);

    String receipt();

    Refs refs();

    void validateOutput(String raw);

    Output output(String raw);

    Normalized normalized(String raw);

    List<Endpoint> endpoints(String session, long from, long to);

    void validateEvent(String raw);

    Source source(String raw);

    String sourceSummary();
  }
}
