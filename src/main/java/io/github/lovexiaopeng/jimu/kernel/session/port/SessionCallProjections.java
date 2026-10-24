package io.github.lovexiaopeng.jimu.kernel.session.port;

/** 原始调用和JSON的惰性投影边界；框架节点与冻结序列化留在适配层。 */
public interface SessionCallProjections {
  boolean searchTask();

  boolean scopeTask();

  Iterable<? extends Call> calls();

  int bytes(String value);

  void complete(boolean redacted);

  record Text(String content, boolean redacted) {}

  interface Call {
    String arguments();

    String name();

    void parseObject(String arguments, int maxBytes);

    Text publicQuery();

    String replaceQuery(String content);

    void append(String arguments);
  }
}
