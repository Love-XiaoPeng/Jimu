package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpSettings;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring and ownership only; private MySQL uses OwnerDeliveryScenario's bounded query-view mode.
 */
class QueryViewAssemblyTest {
  private final DriverManagerDataSource source =
      new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic");
  private final StandardEnvironment environment = testEnvironment();
  private final ModelProfiles models = new ModelProfiles(environment);

  private static StandardEnvironment testEnvironment() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("jimu.test-mode", true)));
    return environment;
  }

  @Test
  void suppliedDependenciesStayIdenticalWhileQueryBoundCollaboratorsAreFresh() throws Exception {
    var manager = new DataSourceTransactionManager(source);
    LedgerUnitOfWork work =
        new LedgerUnitOfWork() {
          public <T> T required(Supplier<T> body) {
            throw new AssertionError("assembly must not enter a transaction");
          }
        };
    Runnable check =
        () -> {
          throw new AssertionError("assembly must not check schema");
        };
    var mappers = LedgerMapperSessions.create(source);
    var root = new JdbcLedger(source, models, manager, work, check, mappers);
    var first = view(root, System.nanoTime() + 2_000_000_000L, new QueryResources(2, 12, 4));
    var second = view(root, System.nanoTime() + 2_000_000_000L, null);
    var shared = (LedgerPersistenceDependencies) field(root, "persistence");
    assertSame(source, shared.source());
    assertSame(manager, shared.manager());
    assertSame(work, shared.unitOfWork());
    assertSame(check, shared.schemaCheck());
    assertSame(mappers, shared.mappers().session());
    for (var query : List.of(first, second)) {
      assertSame(shared, field(query, "persistence"));
      assertSame(root.mappers, query.mappers);
      assertSame(models, query.models);
      assertSame(source, ((JdbcTemplate) field(query, "db")).getDataSource());
      assertSame(
          manager, ((TransactionTemplate) field(query, "transactions")).getTransactionManager());
      assertSame(manager, field(field(query, "queryTransactions"), "manager"));
      assertSame(
          source, ((JdbcTemplate) field(field(query, "queryTransactions"), "db")).getDataSource());
      assertSame(work, field(query, "unitOfWork"));
      for (String name :
          List.of(
              "db",
              "queryTransactions",
              "transactions",
              "sessions",
              "mcp",
              "contextRecords",
              "executionService",
              "workerService",
              "sessionScopes")) assertNotSame(field(root, name), field(query, name), name);
      assertNotSame(root.sessions.context, query.sessions.context);
    }
    assertNotSame(first.queryContext(), second.queryContext());
    assertSame(first, field(first.contextRecords, "ledger"));
    assertSame(second, field(second.contextRecords, "ledger"));
  }

  @Test
  void bothCompatibilityConstructorsResolveOneFallbackManagerAndUnitOfWork() throws Exception {
    for (var root :
        List.of(new JdbcLedger(source, models), new JdbcLedger(source, models, null, null, null))) {
      var derived = view(root, System.nanoTime() + 2_000_000_000L, null);
      var shared = (LedgerPersistenceDependencies) field(root, "persistence");
      assertSame(shared, field(derived, "persistence"));
      assertSame(shared.source(), shared.manager().getDataSource());
      assertSame(
          shared.manager(),
          ((TransactionTemplate) field(shared.unitOfWork(), "transactions"))
              .getTransactionManager());
      assertSame(
          shared.source(),
          shared
              .mappers()
              .session()
              .getSqlSessionFactory()
              .getConfiguration()
              .getEnvironment()
              .getDataSource());
    }
  }

  @Test
  void everyConfigurationIsCopiedWithoutCallingCapabilitiesOrStorage() throws Exception {
    var root = new JdbcLedger(source, models);
    BooleanSupplier availability =
        () -> {
          throw new AssertionError("eager availability");
        };
    ArtifactStore store =
        (ArtifactStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ArtifactStore.class},
                (object, method, args) -> {
                  throw new AssertionError("eager storage");
                });
    // Identity sentinel only; no constructor, filesystem call or physical storage claim.
    var workspace =
        new org.springframework.objenesis.SpringObjenesis().newInstance(SecureWorkspaceStore.class);
    root.nativeSearchAvailability(availability);
    root.imageStorage(store);
    root.directoryWorkspace(workspace);
    root.sessionReadEnabled(true, List.of("synthetic-sensitive"));
    for (String name : flags()) set(root, name, true);
    var mcp = new McpSettings(environment);
    root.mcpSettings(mcp);
    var query = view(root, System.nanoTime() + 2_000_000_000L, null);
    assertSame(availability, query.searchAvailable);
    assertSame(store, field(query, "imageStore"));
    assertSame(workspace, field(query, "directoryWorkspace"));
    assertSame(root.sessions.sensitive(), query.sessions.sensitive());
    assertSame(mcp, query.mcp.configuration());
    for (String name : flags()) assertEquals(true, field(query, name), name);
    // Replacement is a later snapshot; mutation through the same settings reference stays visible.
    root.sessionReadEnabled(false, List.of("replacement"));
    root.mcpSettings(new McpSettings(new StandardEnvironment()));
    root.nativeSearchAvailability(() -> false);
    root.imageStorage(null);
    root.directoryWorkspace(null);
    for (String name : flags()) set(root, name, false);
    var later = view(root, System.nanoTime() + 2_000_000_000L, null);
    assertEquals(List.of("synthetic-sensitive"), query.sessions.sensitive());
    assertEquals(List.of("replacement"), later.sessions.sensitive());
    assertSame(mcp, query.mcp.configuration());
    assertNotSame(mcp, later.mcp.configuration());
    assertSame(availability, query.searchAvailable);
    assertNull(field(later, "imageStore"));
    assertNull(field(later, "directoryWorkspace"));
    for (String name : flags()) {
      assertEquals(true, field(query, name));
      assertEquals(false, field(later, name));
    }
    var values = new java.util.HashMap<String, Object>();
    environment.getPropertySources().addFirst(new MapPropertySource("mutable", values));
    assertFalse(query.mcp.configuration().enabled());
    values.put("jimu.mcp.enabled", true);
    assertTrue(query.mcp.configuration().enabled());
    assertThrows(IllegalStateException.class, () -> query.mcp.configuration().hostId());
  }

  @Test
  void defaultAndUnconfiguredFailureSurviveDerivation() throws Exception {
    var root = new JdbcLedger(source, models);
    var query = view(root, System.nanoTime() + 2_000_000_000L, null);
    for (String name : flags()) assertEquals(false, field(query, name));
    assertFalse(query.searchAvailable.getAsBoolean());
    assertNull(field(query, "imageStore"));
    assertNull(field(query, "directoryWorkspace"));
    assertEquals(
        "SESSION_EVENT_UNSAFE", assertThrows(TaskFailure.class, query.sessions::sensitive).code());
    assertSame(root.mcp.configuration(), query.mcp.configuration());
  }

  @Test
  void priorResourcesAccumulateIndependentlyAndNestedDeadlineNeverExtends() throws Exception {
    var root = new JdbcLedger(source, models);
    long end = System.nanoTime() + 2_000_000_000L;
    var first = view(root, end, new QueryResources(2, 12, 4));
    var second = view(root, end, new QueryResources(2, 12, 4));
    var unmetered = view(root, end, null);
    var result = first.queryContext().result();
    result.row();
    result.column("x", "🙂");
    assertEquals(new QueryResources(3, 25, 6), first.queryContext().resources());
    assertEquals(new QueryResources(2, 12, 4), second.queryContext().resources());
    unmetered.queryContext().result().column("x", "🙂");
    assertEquals(new QueryResources(0, 0, 0), unmetered.queryContext().resources());
    assertEquals(end, first.queryContext().deadlineNanos());
    first.queryContext().restrictDeadline(end - 1_000_000L);
    first.queryContext().restrictDeadline(end + 1_000_000L);
    assertEquals(end - 1_000_000L, first.queryContext().deadlineNanos());
  }

  @Test
  void leaseViewsKeepOriginalTwoSecondCapAndEarlierLeaseDeadline() throws Exception {
    var root = new JdbcLedger(source, models);
    for (long seconds : List.of(60L, 1L, -1L)) {
      var deadline = Instant.now().plusSeconds(seconds);
      var lease =
          new Lease("t", "a", "task", "attempt", 1, "worker", deadline, "goal", List.of(), 1);
      long before = System.nanoTime();
      var query =
          (JdbcLedger)
              invoke(root, "queryView", new Class<?>[] {Lease.class, boolean.class}, lease, true);
      long after = System.nanoTime();
      long end = query.queryContext().deadlineNanos();
      assertTrue(end <= before + 2_000_000_000L + (after - before));
      if (seconds == 1) assertTrue(end < before + 1_100_000_000L);
      if (seconds == -1)
        assertEquals(
            "SESSION_QUERY_TIMEOUT",
            assertThrows(TaskFailure.class, query.queryContext()::remainingMillis).code());
      assertEquals(new QueryResources(0, 0, 0), query.queryContext().resources());
    }
  }

  static JdbcLedger view(JdbcLedger root, long end, QueryResources prior) throws Exception {
    return (JdbcLedger)
        invoke(root, "queryView", new Class<?>[] {long.class, QueryResources.class}, end, prior);
  }

  private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    var method = target.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw new AssertionError(failure.getCause());
    }
  }

  private static List<String> flags() {
    return List.of(
        "questionsEnabled",
        "todosEnabled",
        "directoryEnabled",
        "sessionReadEnabled",
        "sessionSearchEnabled",
        "sessionTraceEnabled",
        "priorSessionSearchEnabled");
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
