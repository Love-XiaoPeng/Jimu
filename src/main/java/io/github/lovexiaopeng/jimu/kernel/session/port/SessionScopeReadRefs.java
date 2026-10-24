package io.github.lovexiaopeng.jimu.kernel.session.port;

/** 共享scope read来源事实及冻结refs编解码；节点只保留在适配层。 */
public interface SessionScopeReadRefs {
  record Binding(String operation, String outcome, String raw, Long bytes, String digest) {}

  Binding binding();

  void validateRefs(String raw);

  int bytes(String raw);

  String hash(String raw);

  void retainNodes(String raw);
}
