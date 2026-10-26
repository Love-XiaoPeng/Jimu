package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.util.List;

/** Lazy owner operations in the caller's existing transaction. */
public interface SessionOwners<R> {
  void bucket(String tenant);

  void lockScope(String tenant, String task);

  List<R> find(String tenant, String task, String actor);

  boolean sessionTask(R task);

  void identity(Identity identity);

  void access(R task);
}
