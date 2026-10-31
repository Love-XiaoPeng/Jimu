package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryProofs.HistoryRow;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Set;

/** Work私有的成功history证明；不持有next_seq/body/计账等可变Map，也不代表当前授权。 */
final class SessionCheckedHistories {
  private record Checked(
      Object token,
      Caller caller,
      long version,
      String policy,
      HistoryRow row,
      LocalDateTime retain,
      String[] strings) {}

  private final SessionQueryContext query;
  private final Object token;
  private final ArrayList<Checked> entries;
  private final Object temporary = new Object();

  SessionCheckedHistories(SessionQueryContext query, Object token) {
    this.query = query;
    this.token = token;
    query.retainScratch(this, 1024);
    entries = new ArrayList<>();
  }

  void remember(Caller caller, long version, String policy, HistoryRow row, LocalDateTime retain) {
    if (row.recordVersion() != 1
        || row.version() != version
        || row.retained() != 1
        || !"PRESENT".equals(row.state())
        || retain == null) return;
    query.retainScratch(temporary, 4096);
    try {
      // 先持有所有原串/record及List扩容的新增容量，再分配数组和追加；旧证明不提前断账。
      long added = 1024;
      String[] strings = {
        caller.tenant(),
        caller.id(),
        caller.owner(),
        caller.capabilities(),
        caller.tools(),
        caller.state(),
        caller.requestDigest(),
        policy,
        row.snapshot(),
        row.digest(),
        row.state()
      };
      for (int i = 0; i < strings.length; i++) {
        query.operation(1);
        if (strings[i] == null || owned(strings[i])) continue;
        boolean duplicate = false;
        for (int j = 0; j < i; j++) {
          query.operation(1);
          if (strings[j] == strings[i]) duplicate = true;
        }
        if (!duplicate) added = Math.addExact(added, 64L + 2L * strings[i].length());
      }
      query.retainScratch(this, Math.addExact(bytes, added));
      bytes += added;
      capacityHighWater = Math.max(capacityHighWater, 1024L + 32L * (entries.size() + 1));
      query.operation(1);
      entries.add(new Checked(token, caller, version, policy, row, retain, strings));
    } finally {
      query.releaseScratch(temporary);
    }
  }

  private long bytes = 1024;
  private long capacityHighWater = 1024;

  private boolean owned(String text) {
    for (var entry : entries)
      for (String held : entry.strings()) {
        query.operation(1);
        if (held == text) return true;
      }
    return false;
  }

  private boolean equal(String a, String b) {
    if (a == b) {
      query.operation(1);
      return true;
    }
    if (a == null || b == null) {
      query.operation(1);
      return false;
    }
    return query.primitive("history.compare", Math.max(a.length(), b.length()), () -> a.equals(b));
  }

  boolean checked(Caller caller, long version, String policy) {
    for (int i = entries.size() - 1; i >= 0; i--) {
      query.operation(1);
      var e = entries.get(i);
      var c = e.caller();
      if (e.token() == token
          && e.version() == version
          && equal(c.tenant(), caller.tenant())
          && equal(c.id(), caller.id())
          && equal(c.owner(), caller.owner())
          && equal(c.capabilities(), caller.capabilities())
          && equal(c.tools(), caller.tools())
          && c.epoch() == caller.epoch()
          && java.util.Objects.equals(c.created(), caller.created())
          && equal(c.requestDigest(), caller.requestDigest())
          && equal(e.policy(), policy)) return true;
    }
    return false;
  }

  void invalidate(Set<SessionQueryWork.Dependency> writes) {
    // 先计完整失效扫描，拒绝时保留原引用，Work已poison；清理仍可直接clear。
    query.operation(1L + entries.size());
    entries.removeIf(
        e -> {
          if (writes == null) return true;
          for (var w : writes) {
            query.operation(1);
            if (equal(e.caller().tenant(), w.tenant())
                && (w.task() == null || equal(e.caller().id(), w.task()))) return true;
          }
          return false;
        });
    // 活引用先移除再收缩；重算按identity共享，保留List历史容量的每项1024高水位。
    // 非空时保守保持已预留的容量；不会因清数字而断开剩余引用的所有权。
    if (entries.isEmpty()) {
      bytes = capacityHighWater;
      query.retainScratch(this, bytes);
    }
  }

  void clear() {
    entries.clear();
    query.releaseScratch(this);
  }
}
