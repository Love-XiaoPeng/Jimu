package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.function.Supplier;

/** 上下文读取的同库事务、当前资格和协议转换端口。 */
public interface SessionContexts {
  <T> T required(Supplier<T> work);

  Task lock(Lease lease);

  record Recent(long seq, String type) {}

  record Target(String sessionId, long upper) {}

  record Scope(String digest, String retainUntil, List<Target> targets) {}

  record Context(int version, String sessionId, long upper, List<Recent> recent, Scope scope) {}

  interface Task {
    String id();

    int version();

    long upper();

    List<Recent> recent(long upper);

    Scope scope();

    String encode(Context context);

    void account();
  }
}
