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
      LocalDateTime retain) {}

  private final SessionQueryContext query;
  private final Object token;
  private final ArrayList<Checked> entries;

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
    // 保守持有每条记录全部字符串；删除跨记录identity去重表与二次扫描。
    // 同一String多处引用也各留一份容量，避免以实际共享为由提前退款。
    query.operation(
        20); // record/list-capacity/field stores; per-string access/arithmetic is separate
    long added = 1024;
    added = Math.addExact(added, retained(caller.tenant()));
    added = Math.addExact(added, retained(caller.id()));
    added = Math.addExact(added, retained(caller.owner()));
    added = Math.addExact(added, retained(caller.capabilities()));
    added = Math.addExact(added, retained(caller.tools()));
    added = Math.addExact(added, retained(caller.state()));
    added = Math.addExact(added, retained(caller.requestDigest()));
    added = Math.addExact(added, retained(policy));
    added = Math.addExact(added, retained(row.snapshot()));
    added = Math.addExact(added, retained(row.digest()));
    added = Math.addExact(added, retained(row.state()));
    long next = Math.addExact(bytes, added);
    query.retainScratch(this, next); // reserve before Checked allocation/List growth
    bytes = next;
    capacityHighWater = Math.max(capacityHighWater, 1024L + 32L * (entries.size() + 1));
    entries.add(new Checked(token, caller, version, policy, row, retain));
  }

  private long retained(String value) {
    query.operation(8); // getter/call/NULL/length/multiply/two additions and receiver handoff
    return value == null ? 0 : Math.addExact(64L, Math.multiplyExact(2L, value.length()));
  }

  private long bytes = 1024;
  private long capacityHighWater = 1024;

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
    // 活引用先移除再收缩；不重算/去重剩余引用，保留List历史容量高水位。
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
