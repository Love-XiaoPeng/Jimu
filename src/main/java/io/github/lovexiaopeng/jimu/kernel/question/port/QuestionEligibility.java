package io.github.lovexiaopeng.jimu.kernel.question.port;

/** Lazy original pin/contract members and same-UoW complete installation row. */
public interface QuestionEligibility<P, C> {
  P pin();

  String version(P pin);

  String digest(P pin);

  long epoch(P pin);

  C contract(String version);

  Installation installation();

  String contractOutputDigest(C contract);

  String cleanupState();

  String bindingSnapshot();

  String hash(String binding);

  String bindingDigest();

  String expectedBinding(String version);

  interface Installation {
    String version();

    String digest();

    long epoch();
  }
}
