package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionContentState;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionGrantState;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Authority;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.AuthorityTarget;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Caller;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Scope;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords.Validated;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskState;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 完整集合校验保持原拒绝次序；只将不可用观察登记到原事务完成后记端口。 */
public final class SessionScopeEligibilityServiceImpl implements SessionScopeEligibilityService {
  private final SessionScopeRecords records;
  private final SnapshotDigest digest;
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  public SessionScopeEligibilityServiceImpl(SessionScopeRecords records, SnapshotDigest digest) {
    this.records = records;
    this.digest = digest;
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  private static TaskFailure unavailable() {
    return new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
  }

  private static String utc(LocalDateTime value) {
    return UTC.format(value);
  }

  private static long bytes(String raw) {
    return raw.getBytes(StandardCharsets.UTF_8).length;
  }

  public void lock(String tenant, String caller) {
    var found = records.callerCapabilities(tenant, caller);
    if (found.size() != 1
        || !("session_event_read,session_search".equals(found.getFirst())
            || io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
                .equals(found.getFirst()))) return;
    var ids = new ArrayList<String>();
    ids.add(caller);
    var targets = records.lockTargets(tenant, caller);
    if (targets.size() > 8) throw corrupt();
    for (var id : targets) if (id != null) ids.add(id);
    lock(tenant, ids);
  }

  public void lock(String tenant, List<String> ids) {
    for (var id : ids.stream().distinct().sorted().toList()) records.lockTask(tenant, id);
  }

  public boolean cleanupSafe(Caller c) {
    return List.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED).contains(c.state())
        && records.writersQuiescent(c)
        && !records.activeInvocations(c)
        && !records.unsafeReservations(c)
        && !records.dispatchedModels(c);
  }

  public Authority snapshot(Caller c, Scope s) {
    if (s.recordVersion() != 1
        || s.authorityVersion() != 1
        || !c.owner().equals(s.owner())
        || !c.tools().equals(s.tools())
        || !digest.hash(records.expectedBinding(c)).equals(s.binding())) throw corrupt();
    if (!SessionGrantState.ACTIVE.equals(s.grant())
        || s.invalidated() != null
        || !SessionContentState.PRESENT.equals(s.content())
        || !s.retained()) throw unavailable();
    records.validateAuthority(c, s.raw());
    if (bytes(s.raw()) != s.bytes() || !digest.hash(s.raw()).equals(s.digest())) throw corrupt();
    var a = records.decodeAuthority(s.raw());
    if (!c.tenant().equals(a.tenant())
        || !c.id().equals(a.caller())
        || !c.owner().equals(a.owner())
        || !c.tools().equals(a.tools())
        || !s.binding().equals(a.binding())
        || !utc(s.created()).equals(a.created())
        || !utc(s.retain()).equals(a.retain())
        || a.targets().size() != s.count()) throw corrupt();
    return a;
  }

  public Validated require(Caller c) {
    try {
      return current(c);
    } catch (TaskFailure failure) {
      if (failure.code().equals(SessionErrorCode.SESSION_SCOPE_UNAVAILABLE))
        records.invalidateAfterTransaction(c);
      throw failure;
    }
  }

  private Validated current(Caller c) {
    var scope = records.scope(c);
    var authority = snapshot(c, scope);
    var history = records.history(c);
    if (!history.retain().equals(scope.retain())) throw corrupt();
    var targets = records.targets(c);
    if (targets.size() != scope.count() || targets.size() > 8) throw corrupt();
    var sources = new ArrayList<Caller>();
    var checked = new java.util.HashMap<String, CheckedSource>();
    String previous = "";
    for (int index = 0; index < targets.size(); index++) {
      var target = targets.get(index);
      var expected = authority.targets().get(index);
      String source = expected.id(), raw = target.raw();
      if (target.version() != 1
          || target.ordinal() != index
          || !SessionContentState.PRESENT.equals(target.content())
          || source.compareTo(previous) <= 0
          || source.equals(c.id())
          || !source.equals(target.source())
          || raw == null
          || !raw.equals(expected.canonical())
          || !digest.hash(raw).equals(target.digest())
          || bytes(raw) != target.bytes()) throw corrupt();
      previous = source;
      try {
        var check = lineage(c) ? checkedSource(c, source, false) : null;
        var st = check == null ? source(c, source, false) : check.caller();
        var sh = check == null ? records.history(st) : check.history();
        if (!st.tools().equals(expected.pins())
            || !sh.binding().equals(expected.binding())
            || sh.nextSeq() - 1 != expected.upperSeq()
            || !utc(sh.retain()).equals(expected.retain())
            || !utc(st.created()).equals(expected.created())) throw unavailable();
        sources.add(st);
        if (check != null) checked.put(source, check);
      } catch (TaskFailure failure) {
        if (failure.status() == 403 || failure.status() == 404 || failure.status() == 410)
          throw unavailable();
        throw failure;
      }
    }
    if (lineage(c)) closed(c, sources, authority, checked);
    return new Validated(scope, authority, List.copyOf(sources));
  }

  private static boolean lineage(Caller c) {
    return io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5.CAPABILITIES
        .equals(c.capabilities());
  }

  /** 每来源只展开一层；全部依赖必须已在原显式集合，绝不自动查找缺失ID。 */
  private void closed(
      Caller caller,
      List<Caller> sources,
      Authority authority,
      java.util.Map<String, CheckedSource> checked) {
    var allowed = new java.util.HashMap<String, AuthorityTarget>();
    for (var t : authority.targets()) allowed.put(t.id(), t);
    int count = 0;
    for (var source : sources) {
      var a = checked.get(source.id()).authority();
      var rows = records.targets(source);
      if (rows.size() != a.targets().size()) throw corrupt();
      String previous = "";
      for (int i = 0; i < rows.size(); i++) {
        if (++count > 64) throw corrupt();
        var expected = a.targets().get(i);
        var r = rows.get(i);
        var known = allowed.get(expected.id());
        if (known == null)
          throw new TaskFailure(400, SessionErrorCode.SESSION_LINEAGE_SCOPE_INVALID);
        if (!known.equals(expected)
            || expected.id().compareTo(previous) <= 0
            || r.version() != 1
            || r.ordinal() != i
            || !expected.id().equals(r.source())
            || !expected.canonical().equals(r.raw())
            || !digest.hash(r.raw()).equals(r.digest())
            || bytes(r.raw()) != r.bytes()
            || !SessionContentState.PRESENT.equals(r.content())) throw corrupt();
        previous = expected.id();
      }
    }
  }

  public Caller source(Caller c, String id, boolean admission) {
    return checkedSource(c, id, admission).caller();
  }

  private record CheckedSource(
      Caller caller, SessionScopeRecords.History history, Authority authority) {}

  private CheckedSource checkedSource(Caller c, String id, boolean admission) {
    var st = records.source(c, id);
    if (!c.capabilities().equals(st.capabilities())
        || id.equals(c.id())
        || !c.owner().equals(st.owner())
        || !c.tools().equals(st.tools())
        || c.epoch() != st.epoch()
        || !cleanupSafe(st)) throw unavailable();
    var h = records.history(st);
    var own = records.scope(st);
    var a = snapshot(st, own);
    if ((!lineage(c) && (own.count() != 0 || !a.targets().isEmpty() || records.hasTargets(st)))
        || h.nextSeq() < 1
        || h.nextSeq() > SessionHistoryPolicyV1.PUBLIC_EVENTS
        || !h.retain().equals(own.retain())) throw unavailable();
    if (admission && !records.admissionRetention(st)) throw unavailable();
    return new CheckedSource(st, h, a);
  }
}
