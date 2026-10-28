package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionScopeCreationService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original admission order, authority policy and UTF8 accounting; no transaction ownership. */
public final class SessionScopeCreationServiceImpl implements SessionScopeCreationService {
  private final SessionScopeCreationRecords records;
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

  public SessionScopeCreationServiceImpl(SessionScopeCreationRecords records) {
    this.records = records;
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  public void create(Row caller, List<String> ids) {
    String tenant = caller.tenant(), id = caller.id();
    LocalDateTime created = caller.created(), retain = created.plusDays(7);
    var targets = new ArrayList<Map<String, Object>>();
    for (String source : ids) {
      try {
        var st = records.source(caller, source);
        var h = records.history(st);
        var expiry = h.retain();
        if (expiry.isBefore(retain)) retain = expiry;
        var target = new LinkedHashMap<String, Object>();
        target.put("session_id", source);
        target.put("history_version", records.version(caller));
        target.put("binding_sha256", h.binding());
        target.put("pins_sha256", st.tools());
        target.put("upper_seq", h.nextSeq() - 1);
        target.put("created_at", UTC.format(st.created()));
        target.put("retain_until", UTC.format(expiry));
        targets.add(target);
      } catch (TaskFailure failure) {
        if (failure.status() == 403 || failure.status() == 404 || failure.status() == 410)
          throw new TaskFailure(404, SessionErrorCode.SESSION_SCOPE_UNAVAILABLE);
        throw failure;
      }
    }
    var authority = new LinkedHashMap<String, Object>();
    authority.put("version", 1);
    authority.put("caller_session_id", id);
    authority.put("tenant_id", tenant);
    authority.put("owner_actor_id", caller.owner());
    authority.put("caller_binding_sha256", records.hash(records.binding(caller)));
    authority.put("caller_tools_sha256", caller.tools());
    authority.put(
        "authority_policy",
        records.version(caller) == 5
            ? "explicit-terminal-closed-v5"
            : "explicit-terminal-independent-v4");
    authority.put("created_at", UTC.format(created));
    authority.put("retain_until", UTC.format(retain));
    authority.put("targets", targets);
    String raw = records.json(authority);
    records.validateAuthority(caller, raw);
    records.insertAuthority(
        tenant,
        id,
        caller.owner(),
        records.hash(records.binding(caller)),
        caller.tools(),
        raw,
        records.hash(raw),
        bytes(raw),
        targets.size(),
        created,
        retain,
        created);
    long bytes = bytes(raw);
    for (int ordinal = 0; ordinal < targets.size(); ordinal++) {
      var target = targets.get(ordinal);
      String targetRaw = records.json(target);
      records.insertAuthorityTarget(
          tenant,
          id,
          ordinal,
          target.get("session_id"),
          targetRaw,
          records.hash(targetRaw),
          bytes(targetRaw));
      bytes += bytes(targetRaw) + bytes(target.get("session_id").toString());
    }
    records.retainAuthorityHistory(retain, tenant, id);
    records.body(caller, bytes);
  }
}
