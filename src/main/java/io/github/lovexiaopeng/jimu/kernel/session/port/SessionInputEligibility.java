package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** 输入codec和当前资格的惰性边界；只在Service选定的分支解析、加载。 */
public interface SessionInputEligibility {
  boolean scopeTask();

  boolean searchTask();

  boolean traceTask();

  String tool();

  String callerId();

  List<String> scopeSearchTargets();

  String scopeReadTarget();

  String traceTarget();

  String searchTarget();

  String nativeReadTarget();

  Scope requireScope();

  interface Scope {
    boolean contains(String id);
  }
}
