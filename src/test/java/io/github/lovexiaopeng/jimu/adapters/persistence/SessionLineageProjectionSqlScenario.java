package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 手工metadata行上的同锁/原reader/故障回滚点；不把坏raw或代理故障称真实授权成功。 */
final class SessionLineageProjectionSqlScenario {
  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "query",
          args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
  static final class Fault implements Interceptor {
    String mode;

    @SuppressWarnings("unchecked")
    public Object intercept(Invocation invocation) throws Throwable {
      var result = invocation.proceed();
      String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
      if ("version".equals(mode) && statement.endsWith("SessionScopeMapper.scope"))
        ((List<Map<String, Object>>) result).getFirst().put("record_version", 2L);
      if ("load".equals(mode) && statement.endsWith("TaskAdmissionMapper.lockLineageScope"))
        throw new IllegalStateException("injected row load failure");
      return result;
    }
  }

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    System.out.println("PASS " + name);
    System.out.flush();
  }

  private static JdbcLedger view(JdbcLedger root) {
    try {
      var factory =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      factory.setAccessible(true);
      var view =
          (JdbcLedger)
              factory.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      view.queryContext().restrictLineage();
      return view;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  static void verify(
      JdbcTemplate db, SqlSessionTemplate mappers, SessionQueryTransaction tx, String task) {
    var root = new JdbcLedger(db.getDataSource(), null, null, null, null, mappers);
    root.sessionLineageEnabled = true;
    root.sessionReadEnabled = true;
    root.priorSessionSearchEnabled = true;
    var pins = new TreeMap<String, PluginPin>();
    for (var installation :
        db.queryForList(
            "SELECT plugin_id,plugin_version,plugin_digest,epoch FROM installation WHERE tenant_id='tenant-a'"))
      pins.put(
          (String) installation.get("plugin_id"),
          new PluginPin(
              (String) installation.get("plugin_version"),
              (String) installation.get("plugin_digest"),
              ((Number) installation.get("epoch")).longValue()));
    String tools = Payloads.json(new ToolSnapshot(1, pins));
    db.update(
        "UPDATE task SET tools_snapshot=?,tools_digest=?,installation_epoch=? WHERE tenant_id='tenant-a' AND id=?",
        tools,
        Payloads.hash(tools),
        pins.values().stream().mapToLong(PluginPin::installationEpoch).sum(),
        task);
    var fault = new Fault();
    mappers.getConfiguration().addInterceptor(fault);
    var v = view(root);
    var query = v.queryContext();
    Map<String, Object>[] held = new Map[3];
    tx.required(
        query,
        () -> {
          long start = query.resources().operations();
          var full = v.locked("tenant-a", task);
          long fullCost = query.resources().operations() - start;
          var sql = mappers.getMapper(TaskAdmissionMapper.class);
          start = query.resources().operations();
          held[0] = SessionAcceptedTaskRows.lineage(sql, query, "tenant-a", task);
          System.out.println(
              "PROJECTION_OPERATIONS "
                  + Payloads.json(
                      Map.of(
                          "fixture",
                          "hand-seeded-metadata",
                          "fullRead",
                          fullCost,
                          "lengthsLoaderOwnershipAndTenColumns",
                          query.resources().operations() - start)));
          check(
              held[0].size() == 10
                  && MapperSessionScopeRecords.caller(full)
                      .equals(MapperSessionScopeRecords.caller(held[0])),
              "actual same-lock ten columns preserve complete Caller");
          v.sessionScopes.terminalAccess.accept(full);
          v.sessionScopes.terminalAccess.accept(held[0]);
          check(true, "actual terminalAccess tools/schema/pins/epoch accepts full and ten columns");
          held[1] = SessionAcceptedTaskRows.load(sql, query, "tenant-a", task, true);
          check(
              held[1].size() == 21 && held[1].get("goal").equals(full.get("goal")),
              "event21 retains goal");
          db.update(
              "UPDATE task SET revision=revision+1,next_event_seq=next_event_seq+1 WHERE tenant_id='tenant-a' AND id=?",
              task);
          held[2] = SessionAcceptedTaskRows.load(sql, query, "tenant-a", task, false);
          check(
              held[2].size() == 20 && !held[2].get("revision").equals(held[1].get("revision")),
              "View20 dynamically rereads after progress");
          return null;
        });
    check(
        java.util.Arrays.stream(held).allMatch(Map::isEmpty) && query.scratchLive() == 65536,
        "three actual Maps lose text references before refund");
    for (String mode : List.of("digest", "tools", "installation")) {
      var before = SessionDependencyScenario.snapshot(db);
      var scoped = view(root);
      var q = scoped.queryContext();
      try {
        tx.required(
            q,
            () -> {
              scoped.locked("tenant-a", task);
              if (mode.equals("digest"))
                db.update(
                    "UPDATE task SET tools_digest=REPEAT('b',64) WHERE tenant_id='tenant-a' AND id=?",
                    task);
              if (mode.equals("tools"))
                db.update(
                    "UPDATE task SET tools_snapshot=NULL WHERE tenant_id='tenant-a' AND id=?",
                    task);
              if (mode.equals("installation"))
                db.update("UPDATE installation SET enabled=FALSE WHERE tenant_id='tenant-a'");
              var row =
                  SessionAcceptedTaskRows.lineage(
                      mappers.getMapper(TaskAdmissionMapper.class), q, "tenant-a", task);
              scoped.sessionScopes.terminalAccess.accept(row);
              throw new AssertionError("must refuse " + mode);
            });
        throw new AssertionError("must rollback " + mode);
      } catch (TaskFailure failure) {
        check(
            failure.status() == (mode.equals("installation") ? 403 : 409),
            "actual reader rejects " + mode);
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)) && q.scratchLive() == 65536,
          "22-table rollback after actual reader " + mode);
    }
    for (String mode :
        List.of(
            "version",
            "load",
            "consume",
            "beforeCommit",
            "getMetaData",
            "getColumnType",
            "getObject",
            "close")) {
      var before = SessionDependencyScenario.snapshot(db);
      var scoped = view(root);
      var q = scoped.queryContext();
      RuntimeException[] first = {null};
      var sqlMarker = new SQLException("injected actual cursor " + mode, "08003", 9001);
      fault.mode = mode;
      try {
        tx.required(
            q,
            () -> {
              db.update(
                  "UPDATE task SET revision=revision+1 WHERE tenant_id='tenant-a' AND id=?", task);
              try {
                var full = scoped.locked("tenant-a", task);
                var row =
                    SessionAcceptedTaskRows.lineage(
                        mappers.getMapper(TaskAdmissionMapper.class), q, "tenant-a", task);
                if (mode.equals("version")) scoped.sessionScopes.require(row);
                if (mode.equals("consume"))
                  throw new IllegalStateException("injected row consumer failure");
                if (mode.equals("beforeCommit")) {
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        public void beforeCommit(boolean readOnly) {
                          throw new IllegalStateException("injected beforeCommit failure");
                        }
                      });
                  return null;
                }
                if (mode.startsWith("get") || mode.equals("close")) {
                  var connection = DataSourceUtils.getConnection(db.getDataSource());
                  try (var statement =
                      connection.prepareStatement(
                          "SELECT goal FROM task WHERE tenant_id='tenant-a' AND id=?")) {
                    statement.setString(1, task);
                    var actual = statement.executeQuery();
                    var proxy =
                        (ResultSet)
                            Proxy.newProxyInstance(
                                ResultSet.class.getClassLoader(),
                                new Class<?>[] {ResultSet.class},
                                (p, method, args) -> {
                                  if (method.getName().equals(mode)) {
                                    if (mode.equals("close")) actual.close();
                                    throw sqlMarker;
                                  }
                                  try {
                                    if (mode.equals("getColumnType")
                                        && method.getName().equals("getMetaData")) {
                                      var metadata = actual.getMetaData();
                                      return Proxy.newProxyInstance(
                                          ResultSetMetaData.class.getClassLoader(),
                                          new Class<?>[] {ResultSetMetaData.class},
                                          (mp, mm, ma) -> {
                                            if (mm.getName().equals(mode)) throw sqlMarker;
                                            try {
                                              return mm.invoke(metadata, ma);
                                            } catch (
                                                java.lang.reflect.InvocationTargetException
                                                    failure) {
                                              throw failure.getCause();
                                            }
                                          });
                                    }
                                    return method.invoke(actual, args);
                                  } catch (java.lang.reflect.InvocationTargetException failure) {
                                    throw failure.getCause();
                                  }
                                });
                    try (var cursor = QueryResults.meter(proxy, q, "private.actualCursorFault")) {
                      cursor.next();
                      if (mode.equals("getColumnType")) cursor.getMetaData().getColumnType(1);
                    }
                  } catch (SQLException failure) {
                    throw new AssertionError(failure);
                  }
                }
                throw new AssertionError("Missing injected failure " + mode + full.size());
              } catch (RuntimeException failure) {
                first[0] = failure;
                if (mode.equals("consume")) SessionQueryTransaction.current(q).poisonProof(failure);
              }
              long operations = q.resources().operations();
              try {
                q.operation(65537);
                throw new AssertionError("first failure not latched");
              } catch (RuntimeException failure) {
                check(failure == first[0], "same first Throwable after catch " + mode);
              }
              check(
                  operations == q.resources().operations(),
                  "no later accounting after failure " + mode);
              return null;
            });
        throw new AssertionError("Expected rollback " + mode);
      } catch (RuntimeException failure) {
        if (!mode.equals("beforeCommit"))
          check(failure == first[0], "original transaction first error " + mode);
        if (mode.equals("version"))
          check(
              failure instanceof TaskFailure f && f.status() == 409,
              "manual Mapper scope version is 409");
        if (mode.startsWith("get") || mode.equals("close"))
          check(failure.getCause() == sqlMarker, "actual cursor SQLState/cause retained " + mode);
      } finally {
        fault.mode = null;
      }
      check(
          before.equals(SessionDependencyScenario.snapshot(db)) && q.scratchLive() == 65536,
          "22-table rollback/release " + mode);
    }
  }
}
