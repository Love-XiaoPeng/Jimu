package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryInvocationReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryInvocationSessionReadReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryInvocationSessionScopeQueryReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryInvocationSessionSearchReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryInvocationSessionTraceReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryModelDispatchReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistorySessionPublicEventReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryStepReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryStepSessionScopeReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryTaskReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryTaskSessionScopeReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.HistoryTaskSessionScopeTargetReadEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionReadReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionScopeQueryReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionSearchReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionTraceReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryModelDispatchReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistorySessionPublicEventReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryStepReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryStepSessionScopeReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskSessionScopeReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskSessionScopeTargetReadMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryRetentions;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同DS/UoW/query的事实转换与具名操作；原始Map只留在适配器。 */
final class MapperSessionHistoryRetentions implements SessionHistoryRetentions {
  private final JdbcLedger ledger;
  private final SessionHistoryRetentionMapper sql;

  MapperSessionHistoryRetentions(JdbcLedger ledger) {
    this.ledger = ledger;
    sql = ledger.mappers.session().getMapper(SessionHistoryRetentionMapper.class);
  }

  public List<Candidate> candidates() {
    return sql.candidates(ledger.queryContext()).stream()
        .map(r -> new Candidate(s(r, "tenant_id"), s(r, "task_id")))
        .toList();
  }

  public CandidateBatch candidateBatch() {
    if (ledger.queryContext() == null) return SessionHistoryRetentions.super.candidateBatch();
    return new CandidateBatch() {
      // LIMIT16，tenant VARCHAR64/task CHAR36；原Map与转换Candidate交接重叠一并预留。
      private static final long BYTES = 1024 + 16L * 2048;
      private final io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query =
          ledger.queryContext();
      private List<Candidate> values;
      private boolean closed;

      {
        query.retainScratch(this, BYTES);
        List<Map<String, Object>> raw = null;
        try {
          raw = sql.candidates(query);
          if (raw.size() > 16) throw JdbcSessionRecords.corrupt();
          values = new ArrayList<>();
          for (var r : raw) {
            String tenant = s(r, "tenant_id"), task = s(r, "task_id");
            query.operation(1L + tenant.length() + task.length());
            if (tenant.length() > 64 || task.length() > 36) throw JdbcSessionRecords.corrupt();
            values.add(new Candidate(tenant, task));
          }
        } catch (RuntimeException | Error failure) {
          close();
          throw failure;
        } finally {
          if (raw != null) {
            for (var row : raw) row.clear();
            raw.clear();
          }
        }
      }

      public java.util.Iterator<Candidate> iterator() {
        if (closed) throw new IllegalStateException("Closed retention candidates");
        return new java.util.Iterator<>() {
          private int index;

          public boolean hasNext() {
            if (closed) throw new IllegalStateException("Closed retention candidates");
            return index < values.size();
          }

          public Candidate next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            query.operation(1);
            return values.get(index++);
          }
        };
      }

      public void close() {
        if (closed) return;
        closed = true;
        if (values != null) {
          values.clear();
          values = null;
        }
        query.releaseScratch(this);
      }
    };
  }

  public void lockBudget(String tenant) {
    ledger.bucket(tenant);
  }

  public LockedHistory lockTask(Candidate c) {
    return bind(ledger.locked(c.tenant(), c.taskId()));
  }

  private List<Map<String, Object>> select(Class<?> mapper, Object wrapper) {
    var args = new HashMap<String, Object>();
    args.put("ew", wrapper);
    args.put("query", ledger.queryContext());
    return ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", args);
  }

  private ContentTable content(
      String name, String identity, List<Map<String, Object>> rows, String... fields) {
    return new ContentTable(
        name,
        rows.stream()
            .map(
                r ->
                    new ContentRow(
                        r.get(identity),
                        Arrays.stream(fields).map(f -> new Body(f, s(r, f))).toList()))
            .toList());
  }

  public String receipt(List<RetainedTable> tables, long bodyBytes, String bindingDigest) {
    var receipt = new LinkedHashMap<String, Object>();
    for (var table : tables) {
      var rows = new ArrayList<Map<String, Object>>();
      for (var row : table.rows()) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", row.identity());
        for (var body : row.bodies())
          value.put(body.field(), Map.of("sha256", body.sha256(), "bytes", body.bytes()));
        rows.add(value);
      }
      receipt.put(table.name(), rows);
    }
    receipt.put("version", 1);
    receipt.put("original_body_bytes", bodyBytes);
    receipt.put("binding_digest", bindingDigest);
    return Payloads.json(receipt);
  }

  private MetadataRow metadataRow(Map<String, Object> row) {
    return new MetadataRow(
        row.values().stream()
            .map(
                v ->
                    v == null
                        ? null
                        : v instanceof LocalDateTime time
                            ? DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                                .format(time)
                            : v.toString())
            .toList());
  }

  LockedHistory bind(Map<String, Object> task) {
    String tenant = s(task, "tenant_id"), taskId = s(task, "id");
    return new LockedHistory() {
      public History history() {
        var found = sql.history(ledger.queryContext(), tenant, taskId);
        if (found.isEmpty())
          throw new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_UNAVAILABLE);
        var h = found.getFirst();
        return new History(
            n(h, "record_version"),
            n(h, "history_version"),
            s(h, "binding_snapshot"),
            s(h, "binding_digest"),
            s(h, "content_state"),
            n(h, "retained") == 1,
            n(h, "body_bytes"));
      }

      public int expectedVersion() {
        return ledger.sessions.version(task);
      }

      public String expectedBinding() {
        return ledger.sessions.contract(task).binding();
      }

      public boolean scoped() {
        return JdbcSessionScopes.task(task);
      }

      public boolean searchable() {
        return JdbcSessionRecords.searchTask(task);
      }

      public boolean invalidated() {
        var rows = sql.scopeInvalidated(ledger.queryContext(), tenant, taskId);
        if (rows.size() != 1) throw JdbcSessionScopes.unavailable();
        return rows.getFirst().get("invalidated_at") != null;
      }

      public String state() {
        return s(task, "state");
      }

      public boolean writersQuiescent() {
        return ledger.writersQuiescent(task);
      }

      public boolean activeInvocations() {
        return !sql.activeInvocations(ledger.queryContext(), tenant, taskId).isEmpty();
      }

      public boolean unsafeReservations() {
        return !sql.unsafeReservations(ledger.queryContext(), tenant, taskId).isEmpty();
      }

      public boolean dispatchedModels() {
        return !sql.dispatchedModels(ledger.queryContext(), tenant, taskId).isEmpty();
      }

      public List<ContentTable> contents() {
        var result = new ArrayList<ContentTable>();
        if (JdbcSessionScopes.lineage(task)) {
          result.add(
              content(
                  "task_session_lineage",
                  "task_id",
                  sql.lineageContents(ledger.queryContext(), tenant, taskId),
                  "genesis_snapshot",
                  "graph_snapshot"));
          result.add(
              content(
                  "invocation_session_lineage_trace",
                  "invocation_id",
                  sql.lineageTraceContents(ledger.queryContext(), tenant, taskId),
                  "normalized_snapshot"));
        }
        var task =
            new LambdaQueryWrapper<HistoryTaskReadEntity>()
                .eq(HistoryTaskReadEntity::getTenantId, tenant)
                .eq(HistoryTaskReadEntity::getRowId, taskId);
        result.add(
            content(
                "task",
                "rowId",
                select(HistoryTaskReadMapper.class, task),
                "request_snapshot",
                "goal",
                "summary"));
        var step =
            new LambdaQueryWrapper<HistoryStepReadEntity>()
                .eq(HistoryStepReadEntity::getTenantId, tenant)
                .eq(HistoryStepReadEntity::getTaskId, taskId);
        result.add(
            content(
                "step",
                "rowId",
                select(HistoryStepReadMapper.class, step),
                "request_snapshot",
                "response_snapshot"));
        var invocation =
            new LambdaQueryWrapper<HistoryInvocationReadEntity>()
                .eq(HistoryInvocationReadEntity::getTenantId, tenant)
                .eq(HistoryInvocationReadEntity::getTaskId, taskId);
        result.add(
            content(
                "invocation",
                "rowId",
                select(HistoryInvocationReadMapper.class, invocation),
                "input_snapshot",
                "output_snapshot"));
        var model_dispatch =
            new LambdaQueryWrapper<HistoryModelDispatchReadEntity>()
                .eq(HistoryModelDispatchReadEntity::getTenantId, tenant)
                .eq(HistoryModelDispatchReadEntity::getTaskId, taskId);
        result.add(
            content(
                "model_dispatch",
                "rowId",
                select(HistoryModelDispatchReadMapper.class, model_dispatch),
                "usage_snapshot"));
        var session_public_event =
            new LambdaQueryWrapper<HistorySessionPublicEventReadEntity>()
                .eq(HistorySessionPublicEventReadEntity::getTenantId, tenant)
                .eq(HistorySessionPublicEventReadEntity::getTaskId, taskId);
        result.add(
            content(
                "session_public_event",
                "seq",
                select(HistorySessionPublicEventReadMapper.class, session_public_event),
                "event_snapshot"));
        var invocation_session_search =
            new LambdaQueryWrapper<HistoryInvocationSessionSearchReadEntity>()
                .eq(HistoryInvocationSessionSearchReadEntity::getTenantId, tenant)
                .eq(HistoryInvocationSessionSearchReadEntity::getTaskId, taskId);
        result.add(
            content(
                "invocation_session_search",
                "invocation_id",
                select(HistoryInvocationSessionSearchReadMapper.class, invocation_session_search),
                "normalized_query_snapshot"));
        var invocation_session_read =
            new LambdaQueryWrapper<HistoryInvocationSessionReadReadEntity>()
                .eq(HistoryInvocationSessionReadReadEntity::getTenantId, tenant)
                .eq(HistoryInvocationSessionReadReadEntity::getTaskId, taskId);
        result.add(
            content(
                "invocation_session_read",
                "invocation_id",
                select(HistoryInvocationSessionReadReadMapper.class, invocation_session_read),
                "read_sources_snapshot"));
        var invocation_session_trace =
            new LambdaQueryWrapper<HistoryInvocationSessionTraceReadEntity>()
                .eq(HistoryInvocationSessionTraceReadEntity::getTenantId, tenant)
                .eq(HistoryInvocationSessionTraceReadEntity::getTaskId, taskId);
        result.add(
            content(
                "invocation_session_trace",
                "invocation_id",
                select(HistoryInvocationSessionTraceReadMapper.class, invocation_session_trace)));
        var task_session_scope =
            new LambdaQueryWrapper<HistoryTaskSessionScopeReadEntity>()
                .eq(HistoryTaskSessionScopeReadEntity::getTenantId, tenant)
                .eq(HistoryTaskSessionScopeReadEntity::getCallerTaskId, taskId);
        result.add(
            content(
                "task_session_scope",
                "caller_task_id",
                select(HistoryTaskSessionScopeReadMapper.class, task_session_scope),
                "authority_snapshot"));
        var task_session_scope_target =
            new LambdaQueryWrapper<HistoryTaskSessionScopeTargetReadEntity>()
                .eq(HistoryTaskSessionScopeTargetReadEntity::getTenantId, tenant)
                .eq(HistoryTaskSessionScopeTargetReadEntity::getCallerTaskId, taskId);
        result.add(
            content(
                "task_session_scope_target",
                "ordinal",
                select(HistoryTaskSessionScopeTargetReadMapper.class, task_session_scope_target),
                "source_task_id",
                "target_snapshot"));
        var step_session_scope =
            new LambdaQueryWrapper<HistoryStepSessionScopeReadEntity>()
                .eq(HistoryStepSessionScopeReadEntity::getTenantId, tenant)
                .eq(HistoryStepSessionScopeReadEntity::getCallerTaskId, taskId);
        result.add(
            content(
                "step_session_scope",
                "step_id",
                select(HistoryStepSessionScopeReadMapper.class, step_session_scope),
                "context_snapshot"));
        var invocation_session_scope_query =
            new LambdaQueryWrapper<HistoryInvocationSessionScopeQueryReadEntity>()
                .eq(HistoryInvocationSessionScopeQueryReadEntity::getTenantId, tenant)
                .eq(HistoryInvocationSessionScopeQueryReadEntity::getCallerTaskId, taskId);
        result.add(
            content(
                "invocation_session_scope_query",
                "invocation_id",
                select(
                    HistoryInvocationSessionScopeQueryReadMapper.class,
                    invocation_session_scope_query),
                "normalized_snapshot",
                "read_refs_snapshot"));
        return result;
      }

      public void purgeScopeQuery() {
        sql.purgeScopeQuery(ledger.queryContext(), tenant, taskId);
      }

      public void purgeLineage() {
        if (ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper
                        .SessionLineageMapper.class)
                .purge(ledger.queryContext(), tenant, taskId)
            != 1) throw JdbcSessionRecords.corrupt();
        sql.purgeLineageTrace(ledger.queryContext(), tenant, taskId);
      }

      public void purgeScopeStep() {
        sql.purgeScopeStep(ledger.queryContext(), tenant, taskId);
      }

      public void purgeScopeTargets() {
        sql.purgeScopeTargets(ledger.queryContext(), tenant, taskId);
      }

      public void purgeScope() {
        sql.purgeScope(ledger.queryContext(), tenant, taskId);
      }

      public void purgePublicEvents() {
        sql.purgePublicEvents(ledger.queryContext(), tenant, taskId);
      }

      public void purgeSearch() {
        sql.purgeSearch(ledger.queryContext(), tenant, taskId);
      }

      public void purgeTrace() {
        sql.purgeTrace(ledger.queryContext(), tenant, taskId);
      }

      public void purgeRead() {
        sql.purgeRead(ledger.queryContext(), tenant, taskId);
      }

      public void purgeInvocations() {
        sql.purgeInvocations(ledger.queryContext(), tenant, taskId);
      }

      public void purgeSteps() {
        sql.purgeSteps(ledger.queryContext(), tenant, taskId);
      }

      public void purgeDispatches() {
        sql.purgeDispatches(ledger.queryContext(), tenant, taskId);
      }

      public void purgeTask() {
        sql.purgeTask(ledger.queryContext(), tenant, taskId);
      }

      public void markPurged(String receipt) {
        sql.markPurged(ledger.queryContext(), receipt, tenant, taskId);
      }

      public void settleHistory(long bytes) {
        sql.settleHistory(ledger.queryContext(), bytes, tenant, taskId);
      }

      public void settleReservation(long bytes) {
        sql.settleReservation(ledger.queryContext(), bytes, tenant, taskId + ":session-history");
      }

      public void releaseBudget(long bytes) {
        sql.releaseBudget(ledger.queryContext(), bytes, tenant);
      }

      public void event() {
        ledger.event(task, "session-history-purged", "session_history.content_deleted", null);
      }

      public List<MetadataRow> metadata() {
        var result = new ArrayList<MetadataRow>();
        if (JdbcSessionScopes.lineage(task)) {
          sql.metadataInvocationSessionLineageTrace(ledger.queryContext(), tenant, taskId)
              .forEach(r -> result.add(metadataRow(r)));
        }
        sql.metadataTask(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataTaskSessionHistory(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataSessionPublicEvent(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataInvocationSessionRead(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataInvocationSessionSearch(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataInvocationSessionTrace(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataTaskSessionScope(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataTaskSessionScopeTarget(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataStepSessionScope(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataInvocationSessionScopeQuery(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataTaskAttempt(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataStep(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataInvocation(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataModelDispatch(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataReservation(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        sql.metadataEvent(ledger.queryContext(), tenant, taskId)
            .forEach(r -> result.add(metadataRow(r)));
        return result;
      }

      public long lineageMetadataBytes() {
        if (!JdbcSessionScopes.lineage(task)) return 0;
        long bytes = 0;
        try (var batch =
            new SessionLineageMetadataBatch(
                ledger.queryContext(),
                () -> sql.metadataTaskSessionLineage(ledger.queryContext(), tenant, taskId))) {
          for (var row : batch) {
            bytes = Math.addExact(bytes, 32);
            for (var value : row.values())
              bytes = Math.addExact(bytes, 8 + batch.scalarBytes(value));
          }
        }
        return bytes;
      }
    };
  }
}
