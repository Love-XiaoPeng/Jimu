package io.github.lovexiaopeng.jimu.kernel.session.port;

/** History creation and body accounting in the caller's existing unit of work. */
public interface SessionHistoryStorage {
  record Binding(String tenant, String task, String snapshot) {}

  Binding binding();

  void validateGoal();

  void insert(Binding binding);

  int initialBytes();

  boolean session();

  int add(long additional);
}
