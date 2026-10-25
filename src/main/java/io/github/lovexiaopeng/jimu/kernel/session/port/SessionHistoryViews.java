package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/** Owner 历史的原事务、共享核权与惰性投影边界；wire 行对象仅由适配层提供。 */
public interface SessionHistoryViews {
  interface Owner {
    <T> T required(Supplier<T> work);

    Projection lock();
  }

  interface History {
    long nextSeq();

    String contentState();

    long retained();

    String retainUntil();
  }

  interface Scope {
    String digest();

    String state();
  }

  interface Projection {
    History history();

    int version();

    String sessionId();

    List<?> recent(long upper);

    long asOfSseSeq();

    boolean scopeTask();

    Scope requireScope();

    String encode(LinkedHashMap<String, Object> value);

    void validateOwner(String raw);
  }

  interface RecentRows {
    int size();
  }

  interface RecentQuery {
    RecentRows read(long upper);
  }
}
