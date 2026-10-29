package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.io.IOException;
import java.time.temporal.TemporalAccessor;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/** test-only旁路观测：副本/序列化/stack/日志不写生产meter；耗时仍在原绝对截止内。 */
@Intercepts({
  @Signature(
      type = Executor.class,
      method = "query",
      args = {
        MappedStatement.class,
        Object.class,
        org.apache.ibatis.session.RowBounds.class,
        org.apache.ibatis.session.ResultHandler.class
      }),
  @Signature(
      type = Executor.class,
      method = "update",
      args = {MappedStatement.class, Object.class})
})
final class SessionHistoryObservation implements Interceptor {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(
              new SimpleModule()
                  .addSerializer(
                      TemporalAccessor.class,
                      new JsonSerializer<TemporalAccessor>() {
                        @Override
                        public void serialize(
                            TemporalAccessor value,
                            JsonGenerator output,
                            SerializerProvider provider)
                            throws IOException {
                          output.writeString(value.toString());
                        }
                      }));

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Test observation serialization failed", failure);
    }
  }

  private static final class Counts {
    int history, writes;
  }

  private final Map<SessionQueryContext, Counts> queries = new IdentityHashMap<>();

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    var command = (MappedStatement) invocation.getArgs()[0];
    if (!(invocation.getArgs()[1] instanceof Map<?, ?> parameters)
        || !(parameters.get("query") instanceof SessionQueryContext query))
      return invocation.proceed();
    String id = command.getId();
    boolean write = command.getSqlCommandType() != SqlCommandType.SELECT;
    boolean history = id.endsWith("SessionHistoryRetentionMapper.history");
    boolean size = id.endsWith("SessionHistoryRetentionMapper.historyBindingSize");
    if (!write && !history && !size) return invocation.proceed();
    var counts = queries.computeIfAbsent(query, ignored -> new Counts());
    if (write) counts.writes++;
    if (history) counts.history++;
    var event = new LinkedHashMap<String, Object>();
    event.put("testOnlyOutsideProductionMeter", true);
    event.put("query", System.identityHashCode(query));
    event.put("statement", id);
    event.put("ordinal", counts.history);
    event.put("writeOrdinal", counts.writes);
    event.put("before", query.resources());
    event.put("beforeGroups", query.groups());
    event.put("beforeLive", query.scratchLive());
    event.put(
        "caller",
        java.util.Arrays.stream(new Exception().getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("io.github.lovexiaopeng.jimu"))
            .map(Object::toString)
            .toList());
    try {
      Object result = invocation.proceed();
      if ((history || size) && result instanceof List<?> rows) {
        event.put("rows", rows);
        if (history && !rows.isEmpty() && rows.getFirst() instanceof Map<?, ?> row) {
          Object binding = row.get("binding_snapshot");
          event.put("bindingUtf16", binding instanceof String text ? text.length() : null);
          event.put(
              "bindingUtf8",
              binding instanceof String text
                  ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  : null);
        }
      }
      event.put("returned", true);
      return result;
    } catch (Throwable failure) {
      event.put("failure", failure.getClass().getName());
      throw failure;
    } finally {
      event.put("after", query.resources());
      event.put("afterGroups", query.groups());
      event.put("afterLive", query.scratchLive());
      event.put("peak", query.scratchPeak());
      System.out.println("HISTORY_OBSERVATION " + json(event));
      System.out.flush();
      event.clear();
    }
  }

  static void snapshot(org.springframework.jdbc.core.JdbcTemplate db, String label) {
    var names =
        List.of(
            "task",
            "task_attempt",
            "step",
            "invocation",
            "model_dispatch",
            "task_session_history",
            "task_session_scope",
            "task_session_scope_target",
            "task_session_lineage",
            "step_session_scope",
            "invocation_session_scope_query",
            "invocation_session_lineage_trace",
            "reservation",
            "event",
            "session_public_event",
            "invocation_session_read",
            "invocation_session_search",
            "invocation_session_trace",
            "tenant_budget",
            "actor_access",
            "installation",
            "worker_process");
    var tables = SessionDependencyScenario.snapshot(db);
    if (tables.size() != names.size()) throw new AssertionError("22 exact tables");
    for (int i = 0; i < names.size(); i++) {
      System.out.println("SNAPSHOT22 " + label + " " + names.get(i) + " " + json(tables.get(i)));
      System.out.flush();
    }
  }
}
