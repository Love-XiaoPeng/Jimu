package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeRecords;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

/** 新参数借用只属于当前FINAL；每次SELECT仍核当时的Work、task、锁与原command。 */
class SessionFinalParametersTest {
  private static final String PREFIX =
      "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryRetentionMapper.metadata";

  private static String statement(Table table) {
    return PREFIX
        + java.util.Arrays.stream(table.name().toLowerCase(Locale.ROOT).split("_"))
            .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
            .collect(java.util.stream.Collectors.joining());
  }

  private static SessionQueryContext query() {
    var q = new SessionQueryContext(System.nanoTime() + 2_000_000_000L, null);
    q.restrictLineage();
    return q;
  }

  private static Object field(Object target, String name) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private static final class Session extends SqlSessionTemplate {
    final Configuration configuration = new Configuration();
    final List<Map<?, ?>> parameters = new ArrayList<>();
    String fault = "";
    Runnable before = () -> {}, closing = () -> {};

    Session() {
      super(new DefaultSqlSessionFactory(new Configuration()), ExecutorType.SIMPLE, null);
      for (var table : Table.values()) {
        if (table == Table.TASK || table == Table.TASK_SESSION_HISTORY) continue;
        configuration.addMappedStatement(
            new MappedStatement.Builder(
                    configuration, statement(table), p -> null, SqlCommandType.SELECT)
                .build());
      }
    }

    @Override
    public Configuration getConfiguration() {
      return configuration;
    }

    @Override
    public void select(String name, Object args, ResultHandler handler) {
      var values = (Map<?, ?>) args;
      parameters.add(values);
      before.run();
      if (fault.equals("error")) throw new AssertionError("select primary");
      var table =
          java.util.Arrays.stream(Table.values())
              .filter(t -> statement(t).equals(name))
              .findFirst()
              .orElseThrow();
      assertEquals(SessionMetadataProjection.forFinalTable(table).statement, name);
      var driver =
          new SessionFinalResultFoldTest.Driver(
              SessionMetadataProjection.forFinalTable(table), List.of());
      var q = (SessionQueryContext) values.get("query");
      try (var rows = driver.result) {
        ((QueryResults.FinalMetadata) handler)
            .read(
                rows,
                fault.equals("query") ? query() : q,
                configuration.getMappedStatement(
                    fault.equals("command") ? statement(Table.EVENT) : name));
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException(failure);
      } finally {
        closing.run();
        q.releaseScratch(driver.result);
      }
    }
  }

  private static Map<String, Object> task() {
    return new HashMap<>(
        Map.of("tenant_id", "t", "id", "c", "capabilities", SessionLineagePolicyV5.CAPABILITIES));
  }

  @Test
  void originalNamesAndOneImmutableParametersMapAreUsedOnlyInsideOneFinalization()
      throws Exception {
    var session = new Session();
    for (int pass = 0; pass < 2; pass++) {
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var accounting =
          new MapperSessionMetadataAccounting(
              new JdbcSessionRecords(null, q, session), task(), true);
      int start = session.parameters.size();
      fixture.transaction.required(
          q,
          () -> {
            SessionQueryTransaction.current(q).locked("t", "c");
            try (var scope = accounting.finalization()) {
              for (var table : Table.values()) {
                if (table == Table.TASK || table == Table.TASK_SESSION_HISTORY) continue;
                assertEquals(0, accounting.finalTable(table).rows());
              }
              var parameters = session.parameters.get(start);
              assertEquals(Map.of("query", q, "tenant", "t", "taskId", "c"), parameters);
              assertThrows(
                  UnsupportedOperationException.class,
                  () -> ((Map<Object, Object>) parameters).put("taskId", "other"));
              for (var p : session.parameters.subList(start, start + 16)) assertSame(parameters, p);
            }
            return null;
          });
      assertNull(field(accounting, "finalParameters"));
      assertNull(field(accounting, "readingCommand"));
      assertEquals(65536, q.scratchLive());
    }
    assertNotSame(session.parameters.get(0), session.parameters.get(16));
  }

  @Test
  void taskReplacementAndMissingCallerLockAfterFirstSelectCannotUsePreparedParameters()
      throws Exception {
    for (var fault : List.of("tenant_id", "id", "lock", "foreign")) {
      var session = new Session();
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var task = task();
      var accounting =
          new MapperSessionMetadataAccounting(new JdbcSessionRecords(null, q, session), task, true);
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.transaction.required(
                  q,
                  () -> {
                    var work = SessionQueryTransaction.current(q);
                    work.locked("t", "c");
                    try (var scope = accounting.finalization()) {
                      accounting.finalTable(Table.TASK_ATTEMPT);
                      if (fault.equals("foreign"))
                        return fixture.transaction.requiresNew(
                            query(), () -> accounting.finalTable(Table.EVENT));
                      if (fault.equals("lock")) {
                        try {
                          ((Map<?, ?>) field(work, "locks")).clear();
                        } catch (Exception failure) {
                          throw new AssertionError(failure);
                        }
                      } else task.put(fault, "other");
                      return accounting.finalTable(Table.EVENT);
                    }
                  }));
      assertEquals(1, session.parameters.size());
      assertNull(field(accounting, "finalParameters"));
      assertEquals(65536, q.scratchLive());
    }
  }

  @Test
  void foreignCommandOrContextAndWritesDuringReadOrCloseKeepFailureAndReleaseMap()
      throws Exception {
    for (var fault : List.of("query", "command", "write", "close", "error")) {
      var session = new Session();
      session.fault = fault;
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var accounting =
          new MapperSessionMetadataAccounting(
              new JdbcSessionRecords(null, q, session), task(), true);
      if (fault.equals("write"))
        session.before = () -> SessionQueryTransaction.current(q).mutation();
      if (fault.equals("close"))
        session.closing = () -> SessionQueryTransaction.current(q).mutation();
      Class<? extends Throwable> expected =
          fault.equals("error") ? AssertionError.class : IllegalStateException.class;
      var caught =
          assertThrows(
              expected,
              () ->
                  fixture.transaction.required(
                      q,
                      () -> {
                        SessionQueryTransaction.current(q).locked("t", "c");
                        try (var scope = accounting.finalization()) {
                          return accounting.finalTable(Table.TASK_ATTEMPT);
                        }
                      }));
      if (fault.equals("error")) assertEquals("select primary", caught.getMessage());
      assertEquals(1, session.parameters.size());
      assertNull(field(accounting, "finalParameters"));
      assertNull(field(accounting, "readingCommand"));
      assertEquals(65536, q.scratchLive());
      var closed = assertThrows(RuntimeException.class, () -> accounting.finalTable(Table.EVENT));
      if (fault.equals("error")) assertSame(caught, closed.getCause());
      assertEquals(1, session.parameters.size());
    }
  }

  private static SessionScopeRecords.SourceFact source(String tenant, String id) {
    var caller =
        new SessionScopeRecords.Caller(
            tenant, id, "owner", "caps", "tools", 1, "SUCCEEDED", null, "d");
    return new SessionScopeRecords.SourceFact(
        caller,
        new SessionScopeRecords.History(1, "b", null),
        new SessionScopeRecords.Scope(
            1, 1, "owner", "b", "tools", "{}", "d", 2, 0, "ACTIVE", null, null, null, null, null,
            "PRESENT", true),
        new SessionScopeRecords.Authority(tenant, id, "owner", "tools", "b", "", "", List.of()),
        List.of());
  }

  @Test
  void preparedMapStillChecksEveryNewAndReplacedSourceLock() throws Exception {
    for (var missing : List.of("s1", "s2", "replacement")) {
      var session = new Session();
      var fixture = new SessionQueryTransactionTest.Fixture();
      var q = query();
      var accounting =
          new MapperSessionMetadataAccounting(
              new JdbcSessionRecords(null, q, session), task(), true);
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.transaction.required(
                  q,
                  () -> {
                    var work = SessionQueryTransaction.current(q);
                    work.admission();
                    for (String id : List.of("c", "s1", "s2")) work.locked("t", id);
                    var caller = source("t", "c").caller();
                    work.stageSource(caller, source("t", "s1"));
                    try (var scope = accounting.finalization()) {
                      accounting.finalTable(Table.TASK_ATTEMPT);
                      work.stageSource(caller, source("t", "s2"));
                      Map<?, ?> locks;
                      try {
                        locks = (Map<?, ?>) field(work, "locks");
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                      if (missing.equals("replacement")) {
                        work.locked("other", "s1");
                        work.stageSource(caller, source("other", "s1"));
                        locks.remove("t:s1");
                        accounting.finalTable(Table.EVENT);
                        locks.remove("other:s1");
                      } else locks.remove("t:" + missing);
                      return accounting.finalTable(Table.EVENT);
                    }
                  }));
      assertEquals(missing.equals("replacement") ? 2 : 1, session.parameters.size());
      assertNull(field(accounting, "finalParameters"));
      assertEquals(65536, q.scratchLive());
    }
  }
}
