package io.github.lovexiaopeng.jimu.kernel.question.port;

/** Lazy original row members, pin and historical codec; no persistence operation. */
public interface QuestionInput<P, R> {
  long recordVersion();

  long questionVersion();

  String bindingDigest();

  String taskBindingDigest();

  String requestSnapshot();

  String hash(String raw);

  String requestDigest();

  P pin();

  String version(P pin);

  R input(String version, String raw);
}
