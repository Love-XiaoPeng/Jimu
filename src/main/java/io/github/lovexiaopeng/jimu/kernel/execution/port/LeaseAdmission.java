package io.github.lovexiaopeng.jimu.kernel.execution.port;

/** 已按run/budget/task锁序取得的惰性准入事实及外部能力。 */
public interface LeaseAdmission {
  String modelRejection();

  String runRejection();

  void requireScope();

  void checkMaterials();

  void access();

  boolean session();

  boolean directory();

  boolean todo();

  boolean question();

  String owner();

  void sessionAdmission();

  void directoryAdmission();

  boolean todosEnabled();

  String todoContentState();

  void requireTodoModel();

  boolean questionsEnabled();

  boolean cancelled();

  boolean withinDeadline();
}
