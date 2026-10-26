package io.github.lovexiaopeng.jimu.kernel.event.port;

/** 调用者已持有任务锁的事件事实和同UoW变更。 */
public interface EventRecords {
  boolean exists(String fact);

  boolean todo();

  boolean directory();

  boolean session();

  long sequence();

  void advance();

  void insert(long seq, String fact, String type, String code, String artifact);

  void sessionFact(String fact);

  void sessionAccount(boolean closing);

  void directoryAccount(boolean closing);

  void todoAccount(boolean closing);
}
