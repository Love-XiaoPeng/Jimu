package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** 原生read的来源、原始槽位和直接端点事实；不递归读取旧read输出。 */
public interface SessionReadRelationProofs {
  record SourceBinding(Long version, String state, String raw, Long bytes, String digest) {}

  record Source(boolean object, int fields, boolean integral, long seq, String digest) {}

  record SourceDocument(boolean array, int size, List<Source> values) {}

  interface Sources {
    SourceBinding sourceBinding();

    SourceDocument sourceDocument(String raw);

    int bytes(String raw);

    String hash(String raw);
  }

  record Step(Long upper, String response, String responseDigest) {}

  record Invocation(
      String input,
      String inputDigest,
      String tool,
      String slot,
      String provider,
      String output,
      String pluginVersion,
      String pluginDigest,
      Long epoch) {}

  record Binding(
      String binding,
      Long upper,
      String inputDigest,
      Long target,
      Long before,
      Long after,
      String outcome,
      Long outputBytes,
      Long start,
      Long end,
      String targetDigest) {}

  record Input(String session, long seq, int before, int after) {}

  record Call(String id, String name, String arguments) {}

  record Response(int version, List<Call> calls) {}

  record Pin(String version, String digest, long epoch) {}

  record Output(
      long upper,
      String boundary,
      String session,
      long targetSeq,
      long from,
      long to,
      int before,
      int after,
      String target) {}

  interface Proof extends Sources {
    String sequences(List<Source> values);

    String eventSequences();

    Step step();

    void validateStep();

    String historyBinding();

    Invocation invocation();

    Binding binding();

    Input input(String raw);

    String session();

    Response response(String raw);

    Pin pin();

    void validateOutput(String raw);

    Output output(String raw);

    long eventSeq();

    String endpointDigest(long seq);
  }
}
