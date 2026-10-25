package io.github.lovexiaopeng.jimu.kernel.session.port;

/** 检查点的惰性解码、锁定历史及同事务 step/context 写入边界。 */
public interface SessionCheckpointPreparation {
  interface Checkpoint {
    String requestBody();

    boolean messagesEmpty();

    String lastRole();

    String lastContent();
  }

  interface History {
    long nextSeq();

    String bindingDigest();
  }

  interface Scope {
    String authorityDigest();
  }

  Checkpoint decode(String request);

  String context();

  History lockHistory();

  void updateStep(long upper, String contextDigest, String bindingDigest);

  boolean scopeTask();

  Scope requireScope();

  void insertScope(String context, Scope scope);

  void body(long additional);
}
