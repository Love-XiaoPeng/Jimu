package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.lovexiaopeng.jimu.adapters.persistence.artifact.entity.ArtifactViewEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransaction;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryTransactionTest;
import io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskVisibilityEntity;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskOperationsServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实Service/Mapper适配/owner，只有Mapper返回与JDBC事务替身；不是自然M2/维护持久化。 */
class JdbcLedgerAccessFailureTest {
  @Test
  void healthyActorDenialKeepsNamedStatusRedaction() throws Exception {
    var h = new Harness();
    var result =
        h.tx.transaction.required(
            h.query,
            () -> {
              h.skipUnmodelledObservation();
              var view = h.service.get(new Tasks.Identity("t", "a"), "c");
              assertNull(h.query.recordedFailure());
              return view;
            });
    assertEquals("SESSION_SCOPE_UNAVAILABLE", result.code());
    assertNull(result.summary());
    assertTrue(result.artifacts().isEmpty());
    assertEquals(1, h.tx.commits);
  }

  @Test
  void protectedActorDenialStaysOriginal403AfterServiceCatchAndSecondaryFailures()
      throws Exception {
    var h = new Harness();
    RuntimeException[] first = {null};
    var observed =
        assertThrows(
            RuntimeException.class,
            () ->
                h.tx.transaction.required(
                    h.query,
                    () -> {
                      SessionQueryTransaction.current(h.query).protectedWrite();
                      // Service既有状态分支可能catch原403；finish仍必须回滚。
                      h.service.get(new Tasks.Identity("t", "a"), "c");
                      first[0] = h.query.recordedFailure();
                      assertEquals(403, ((TaskFailure) first[0]).status());
                      long count = h.query.resources().operations();
                      h.query.restrictDeadline(System.nanoTime() - 1);
                      assertSame(
                          first[0],
                          assertThrows(RuntimeException.class, () -> h.query.operation(65537)));
                      assertSame(
                          first[0], assertThrows(RuntimeException.class, h.query::checkHealthy));
                      assertEquals(count, h.query.resources().operations());
                      return null;
                    }));
    assertSame(first[0], observed);
    assertEquals(0, h.tx.commits);
    assertEquals(1, h.tx.rollbacks);
  }

  @Test
  void sqlAndCorruptionCaughtByCallerStillPoisonOwnerWithOriginalCause() throws Exception {
    for (RuntimeException marker :
        List.of(
            new TaskFailure(409, "SESSION_HISTORY_CORRUPT"),
            new DataAccessResourceFailureException(
                "SQL marker", new SQLException("private", "HY000")))) {
      var h = new Harness();
      h.template.failure = marker;
      var observed =
          assertThrows(
              RuntimeException.class,
              () ->
                  h.tx.transaction.required(
                      h.query,
                      () -> {
                        assertSame(
                            marker,
                            assertThrows(
                                RuntimeException.class, () -> h.ledger.access(h.template.task)));
                        assertSame(
                            marker,
                            assertThrows(RuntimeException.class, () -> h.query.operation(65537)));
                        return null;
                      }));
      assertSame(marker, observed);
      if (marker.getCause() instanceof SQLException sql) assertEquals("HY000", sql.getSQLState());
      assertEquals(1, h.tx.rollbacks);
    }
  }

  @Test
  void foreignQueryPoisonsActualOwnerBeforeMapperAccess() throws Exception {
    var h = new Harness();
    var other = h.view();
    RuntimeException[] first = {null};
    var observed =
        assertThrows(
            RuntimeException.class,
            () ->
                h.tx.transaction.required(
                    h.query,
                    () -> {
                      first[0] =
                          assertThrows(
                              IllegalStateException.class, () -> other.access(h.template.task));
                      assertEquals(0, h.template.actorCalls);
                      return null;
                    }));
    assertSame(first[0], observed);
    assertEquals(1, h.tx.rollbacks);
  }

  @Test
  void artifactProviderSelectsOnlyPublicFiveColumnsAndRefusesBeforeMaterializing()
      throws Exception {
    for (int rows : List.of(0, 1, 2)) {
      var h = new Harness();
      h.template.rows = rows;
      var response = h.response();
      var artifacts = new MapperTaskOperations(h.ledger).row(h.template.task).artifacts();
      assertEquals(rows, artifacts.size());
      assertEquals("id,media_type,size,sha256,expires_at", h.template.selection);
      assertTrue(h.template.liveAtProvider > 65536 + 1024);
      assertTrue(h.query.scratchLive() > 65536);
      response.close();
      response.produced();
      assertEquals(65536, h.query.scratchLive());
    }
    var h = new Harness();
    h.template.textBytes = 1048576;
    var response = h.response();
    assertThrows(
        TaskFailure.class,
        () -> new MapperTaskOperations(h.ledger).row(h.template.task).artifacts());
    assertEquals(0, h.template.providerCalls);
    response.close();
    response.produced();
    assertEquals(65536, h.query.scratchLive());
  }

  private static final class Harness {
    final SessionQueryTransactionTest.Fixture tx = new SessionQueryTransactionTest.Fixture();
    final DataSource source;
    final Template template;
    final JdbcLedger ledger;
    final SessionQueryContext query;
    final TaskOperationsServiceImpl service;

    Harness() throws Exception {
      var field = tx.getClass().getDeclaredField("source");
      field.setAccessible(true);
      source = (DataSource) field.get(tx);
      template = new Template(source);
      var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "access-test");
      TableInfoHelper.initTableInfo(assistant, TaskVisibilityEntity.class);
      TableInfoHelper.initTableInfo(assistant, ArtifactViewEntity.class);
      ledger = view();
      query = ledger.queryContext();
      query.restrictLineage();
      template.query = query;
      service =
          new TaskOperationsServiceImpl(
              new MapperTaskOperations(ledger),
              new LedgerUnitOfWork() {
                public <T> T required(Supplier<T> action) {
                  return action.get();
                }
              });
    }

    JdbcLedger view() throws Exception {
      var root = new JdbcLedger(source, null, null, null, null, template);
      var method =
          JdbcLedger.class.getDeclaredMethod("queryView", long.class, QueryResources.class);
      method.setAccessible(true);
      var result =
          (JdbcLedger)
              method.invoke(root, System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
      result.queryContext().restrictLineage();
      return result;
    }

    void skipUnmodelledObservation() {
      // 隔离状态分支：本测试不建模REQUIRES_NEW失效维护，不把此分支称持久化通过。
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public int getOrder() {
              return Integer.MIN_VALUE;
            }

            public void afterCompletion(int status) {
              query.restrictDeadline(System.nanoTime() - 1);
            }
          });
    }

    SessionTaskResponse response() {
      var response = new SessionTaskResponse();
      response.bind(
          new SessionTaskResponse.Budget() {
            public void retain(Object owner, long bytes) {
              query.retainScratch(owner, bytes);
            }

            public void release(Object owner) {
              query.releaseScratch(owner);
            }

            public void wire(int count) {
              query.operation(count);
            }
          });
      ledger.taskResponse = response;
      return response;
    }
  }

  private static final class Template extends SqlSessionTemplate {
    final Map<String, Object> task = new HashMap<>();
    SessionQueryContext query;
    RuntimeException failure;
    int actorCalls, providerCalls, rows = 1;
    long textBytes = 75, liveAtProvider;
    String selection;

    Template(DataSource source) {
      super(
          new DefaultSqlSessionFactory(
              new Configuration(
                  new Environment("access-test", new JdbcTransactionFactory(), source))));
      task.putAll(
          Map.of(
              "tenant_id",
              "t",
              "id",
              "c",
              "owner_actor_id",
              "a",
              "capabilities",
              SessionLineagePolicyV5.CAPABILITIES,
              "state",
              "QUEUED",
              "revision",
              1L,
              "next_event_seq",
              1L,
              "created_at",
              LocalDateTime.of(2026, 10, 29, 1, 2),
              "updated_at",
              LocalDateTime.of(2026, 10, 29, 1, 2),
              "summary",
              "secret"));
    }

    @SuppressWarnings("unchecked")
    public <T> T getMapper(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "lockActor" -> {
                      actorCalls++;
                      if (failure != null) throw failure;
                      yield List.of(Map.of("enabled", false));
                    }
                    case "selectBudgetForUpdate", "lockTask" ->
                        List.of(Map.of("tenant_id", "t", "id", "c"));
                    case "selectTaskForUpdate" -> List.of(task);
                    case "callerCapabilities" ->
                        List.of(Map.of("capabilities", SessionLineagePolicyV5.CAPABILITIES));
                    case "lockTargets" -> List.of();
                    case "publishedArtifactSizes" ->
                        List.of(Map.of("row_count", (long) rows, "text_bytes", textBytes));
                    default -> method.getReturnType() == int.class ? 0 : List.of();
                  });
    }

    @SuppressWarnings("unchecked")
    public <E> List<E> selectList(String statement, Object parameter) {
      if (statement.contains("TaskVisibilityMapper"))
        return (List<E>) List.of(Map.of("capabilities", SessionLineagePolicyV5.CAPABILITIES));
      if (!statement.contains("ArtifactViewMapper")) throw new AssertionError(statement);
      providerCalls++;
      selection = ((LambdaQueryWrapper<?>) ((Map<?, ?>) parameter).get("ew")).getSqlSelect();
      liveAtProvider = query.scratchLive();
      var row = new HashMap<String, Object>();
      row.putAll(
          Map.of("id", "a", "media_type", "text/plain", "sha256", "d".repeat(64), "size", 1L));
      row.put("expires_at", null);
      return (List<E>)
          java.util.stream.IntStream.range(0, rows).mapToObj(i -> new HashMap<>(row)).toList();
    }
  }
}
