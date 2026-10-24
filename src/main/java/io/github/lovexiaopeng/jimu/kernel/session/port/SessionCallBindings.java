package io.github.lovexiaopeng.jimu.kernel.session.port;

/** Typed lazy binding boundary; original codecs, row conversion and MP writes stay in adapters. */
public interface SessionCallBindings {
  void lockHistory();

  void loadStep();

  boolean hasUpper();

  String historyBinding();

  String stepBinding();

  Iterable<? extends Invocation> invocations();

  boolean scopeTask();

  boolean traceTask();

  boolean searchTask();

  interface Invocation {
    String tool();

    void parseTrace();

    void insertTrace();

    void normalizeSearch();

    void chargeSearch();

    void insertSearch();

    void parseRead();

    void prepareRead();

    void insertRead(int relationVersion);

    void requireScope();

    void normalizeScope();

    void loadCut();

    String authority();

    String cutAuthority();

    String stepContext();

    String cutContext();

    void insertScope();

    void chargeScope();
  }
}
