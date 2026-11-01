package io.github.lovexiaopeng.jimu.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.health.LedgerProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeSearchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionSearchBodyFilter;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableControlConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableEndpointConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableLedgerConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.DurableRuntimeConfiguration;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Real Spring factory-method resolution; no DB, filesystem tools, worker start or provider calls.
 */
class DurableAssemblyTest {
  private static final List<Class<?>> GROUPS =
      List.of(
          DurableLedgerConfiguration.class,
          DurableEndpointConfiguration.class,
          DurableRuntimeConfiguration.class,
          DurableControlConfiguration.class);
  private static final Set<String> COMMON =
      Set.of(
          "sessionSearchBodyFilter",
          "modelProfiles",
          "ledger",
          "taskService",
          "artifactService",
          "artifactStore",
          "authenticator",
          "durableReadiness",
          "boundaryProbe",
          "mcpSettings",
          "mcpRuntime",
          "operationThreads",
          "nativeSearchRuntime",
          "eventFeed");

  @Test
  void disabledAndMissingFlagSuppressEveryGroupEvenWhenScannedAndImported() {
    for (String enabled : List.of("false", "missing")) {
      try (var context = new AnnotationConfigApplicationContext()) {
        if (!enabled.equals("missing"))
          properties(context, Map.of("jimu.durable.enabled", enabled));
        context.register(DurableConfiguration.class);
        context.scan("io.github.lovexiaopeng.jimu.bootstrap.config");
        context.refresh();
        for (var group : GROUPS) {
          assertTrue(context.getBeansOfType(group).isEmpty());
          for (var method : group.getDeclaredMethods()) {
            assertFalse(context.containsBean(method.getName()));
          }
        }
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(DataSourceTransactionManager.class).isEmpty());
        assertTrue(context.getBeansOfType(LedgerUnitOfWork.class).isEmpty());
      }
    }
  }

  @Test
  void controlAndWorkerGraphsResolveOnceWithOptionalImagesAndOnePersistenceAssembly()
      throws Exception {
    for (String role : List.of("control", "worker")) {
      for (boolean images : List.of(false, true)) {
        try (var context = graph(role, images, Map.of())) {
          var expected = new java.util.HashSet<>(COMMON);
          expected.addAll(
              role.equals("worker")
                  ? Set.of("durableAgent", "workerLoop", "publicWebReader")
                  : Set.of("maintenance", "supervisor"));
          if (images) expected.add("imageStore");
          var actual = new java.util.HashSet<String>();
          for (var group : GROUPS) {
            assertFalse(group.getAnnotation(Configuration.class).proxyBeanMethods());
            assertEquals(1, context.getBeansOfType(group).size());
            for (var method : group.getDeclaredMethods()) {
              if (context.containsBean(method.getName())) {
                assertNotNull(context.getBean(method.getName()));
                actual.add(method.getName());
              }
            }
          }
          assertEquals(expected, actual);
          assertEquals(1, context.getBeansOfType(DataSource.class).size());
          assertEquals(1, context.getBeansOfType(DataSourceTransactionManager.class).size());
          assertEquals(1, context.getBeansOfType(LedgerUnitOfWork.class).size());
          assertSame(
              context.getBean(DataSource.class),
              context.getBean(DataSourceTransactionManager.class).getDataSource());
          assertSame(
              context.getBean(DataSource.class),
              context
                  .getBean(SqlSessionTemplate.class)
                  .getSqlSessionFactory()
                  .getConfiguration()
                  .getEnvironment()
                  .getDataSource());
          assertEquals(images ? 2 : 1, context.getBeansOfType(ArtifactStore.class).size());
          assertSame(context.getBean("artifactStore"), context.getBean(ArtifactStore.class));
          assertTrue(context.getBeanFactory().getBeanDefinition("artifactStore").isPrimary());
          var filter = context.getBean("sessionSearchBodyFilter", FilterRegistrationBean.class);
          assertTrue(filter.getFilter() instanceof SessionSearchBodyFilter);
          assertTrue(filter.isAsyncSupported());
          assertEquals(
              Set.of("/api/tasks/*", "/api/session-search/tasks", "/api/session-trace/tasks"),
              Set.copyOf(filter.getUrlPatterns()));
          assertFalse(context.getBean(NativeSearchRuntime.class).available());
          assertNotNull(context.getBean(LedgerProbe.class));
          if (role.equals("worker")) {
            assertSame(
                context.getBean("artifactStore"),
                field(context.getBean(DurableAgent.class), "store"));
            var dependencies =
                Arrays.asList(context.getBeanFactory().getDependenciesForBean("durableAgent"));
            assertTrue(
                dependencies.containsAll(
                    List.of(
                        "ledger",
                        "artifactStore",
                        "publicWebReader",
                        "boundaryProbe",
                        "modelProfiles",
                        "nativeSearchRuntime",
                        "mcpRuntime",
                        "operationThreads")));
            assertEquals(images, dependencies.contains("imageStore"));
          }
        }
      }
    }
  }

  @Test
  void closeAndStartMetadataRemainOwnedBySingleBeans() {
    var explicit =
        Map.of(
            "durableReadiness", List.of("", "close"),
            "durableAgent", List.of("", "close"),
            "workerLoop", List.of("start", "close"),
            "eventFeed", List.of("", "close"),
            "maintenance", List.of("start", "close"),
            "supervisor", List.of("start", "close"));
    var seen = new java.util.HashSet<String>();
    for (var group : GROUPS) {
      for (var method : group.getDeclaredMethods()) {
        var bean = method.getAnnotation(Bean.class);
        assertNotNull(bean);
        assertTrue(seen.add(method.getName()), "duplicate factory " + method.getName());
        var lifecycle = explicit.getOrDefault(method.getName(), List.of("", "(inferred)"));
        assertEquals(lifecycle.get(0), bean.initMethod());
        assertEquals(lifecycle.get(1), bean.destroyMethod());
      }
    }
    assertEquals(20, seen.size());
    assertEquals(0, DurableConfiguration.class.getDeclaredMethods().length);
    assertFalse(DurableConfiguration.class.getAnnotation(Configuration.class).proxyBeanMethods());
  }

  @Test
  void identityRejectsMalformedMappingBeforeReadingSigningKey() {
    for (String subjects :
        List.of("s:tenant", "s:bad.tenant:actor", "s:tenant:", "s:tenant:bad actor")) {
      try (var context = lazy(Map.of("jimu.identity.subjects", subjects))) {
        assertEquals("Invalid principal mapping", cause(context, "authenticator").getMessage());
      }
    }
    try (var context = lazy(Map.of("jimu.identity.subjects", "s:tenant:actor"))) {
      assertTrue(cause(context, "authenticator").getMessage().contains("jimu.identity.key"));
    }
    try (var context =
        lazy(
            Map.of(
                "jimu.identity.subjects", "s:tenant:actor;", "jimu.identity.key", signingKey()))) {
      var failure =
          assertThrows(
              TaskFailure.class, () -> context.getBean(Authenticator.class).authenticate(null));
      assertEquals("UNAUTHENTICATED", failure.code());
    }
  }

  @Test
  void boundaryAndNativeDependenciesRetainGatesDefaultsAndFirstRequiredProperty() {
    try (var context = lazy(Map.of("jimu.test.boundary", "INJECT"))) {
      assertEquals(
          "Fault injection requires test assembly", cause(context, "boundaryProbe").getMessage());
    }
    try (var context = lazy(Map.of("jimu.test.boundary", "INJECT", "jimu.test-mode", true))) {
      assertNotNull(context.getBean("boundaryProbe"));
      assertFalse(context.getBean(NativeSearchRuntime.class).available());
    }
    try (var context = lazy(Map.of("jimu.search.enabled", true))) {
      assertTrue(cause(context, "nativeSearchRuntime").getMessage().contains("jimu.search.root"));
    }
    try (var context = lazy(Map.of("jimu.images.enabled", true))) {
      assertTrue(cause(context, "imageStore").getMessage().contains("jimu.images.root"));
    }
    try (var context = lazy(Map.of())) {
      assertTrue(cause(context, "artifactStore").getMessage().contains("jimu.storage-root"));
      assertEquals(
          "Explicit model profiles required outside test assembly",
          cause(context, "modelProfiles").getMessage());
    }
  }

  private static Throwable cause(AnnotationConfigApplicationContext context, String name) {
    Throwable failure = assertThrows(BeanCreationException.class, () -> context.getBean(name));
    while (failure.getCause() != null) failure = failure.getCause();
    return failure;
  }

  @Test
  void ledgerRequiresRoleThenControlMigrationPropertiesBeforeValidation() throws Exception {
    var method =
        Arrays.stream(DurableLedgerConfiguration.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("ledger"))
            .findFirst()
            .orElseThrow();
    method.setAccessible(true);
    var values = new LinkedHashMap<String, Object>();
    var required =
        List.of("jimu.role", "jimu.db.url", "jimu.db.migration-user", "jimu.db.migration-password");
    for (String property : required) {
      var environment = new org.springframework.core.env.StandardEnvironment();
      environment.getPropertySources().addFirst(new MapPropertySource("test", values));
      Object[] arguments = new Object[method.getParameterCount()];
      arguments[5] = environment;
      var failure =
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () -> method.invoke(new DurableLedgerConfiguration(), arguments));
      assertTrue(failure.getCause() instanceof IllegalStateException);
      assertTrue(failure.getCause().getMessage().contains(property));
      values.put(property, property.equals("jimu.role") ? "control" : "synthetic");
    }
    var reads = new java.util.ArrayList<String>();
    var environment =
        new org.springframework.core.env.StandardEnvironment() {
          @Override
          public String getRequiredProperty(String key) {
            reads.add(key);
            if (key.equals("jimu.role")) return "worker";
            throw new AssertionError("worker must validate before reading " + key);
          }
        };
    Object[] arguments = new Object[method.getParameterCount()];
    arguments[0] =
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, called, args) -> {
              throw new java.sql.SQLException("synthetic validation boundary");
            });
    arguments[5] = environment;
    var failure =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> method.invoke(new DurableLedgerConfiguration(), arguments));
    Throwable root = failure.getCause();
    while (root.getCause() != null) root = root.getCause();
    assertEquals("synthetic validation boundary", root.getMessage());
    assertEquals(List.of("jimu.role"), reads);
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static AnnotationConfigApplicationContext lazy(Map<String, Object> values) {
    var context = new AnnotationConfigApplicationContext();
    var properties = new LinkedHashMap<String, Object>(values);
    properties.put("jimu.durable.enabled", true);
    properties(context, properties);
    context.register(DurableConfiguration.class);
    context.addBeanFactoryPostProcessor(
        factory -> {
          for (String name : factory.getBeanDefinitionNames())
            factory.getBeanDefinition(name).setLazyInit(true);
        });
    context.refresh();
    return context;
  }

  private static AnnotationConfigApplicationContext graph(
      String role, boolean images, Map<String, Object> overrides) throws Exception {
    var context = new AnnotationConfigApplicationContext();
    var values =
        new LinkedHashMap<String, Object>(
            Map.of(
                "jimu.durable.enabled",
                true,
                "jimu.role",
                role,
                "jimu.test-mode",
                true,
                "jimu.db.url",
                "jdbc:mysql://127.0.0.1:1/unused",
                "jimu.db.user",
                "unused",
                "jimu.db.password",
                "synthetic",
                "jimu.provider-origin",
                "http://127.0.0.1:1",
                "jimu.identity.subjects",
                "s:tenant:actor",
                "jimu.identity.key",
                signingKey(),
                "jimu.fixture.credentials",
                "tenant:synthetic"));
    values.put("jimu.host-id", "00000000-0000-0000-0000-000000000001");
    values.put("jimu.images.enabled", images);
    values.putAll(overrides);
    properties(context, values);
    context.register(DurableConfiguration.class);
    // Exercise import + scan together; Spring must deduplicate configuration classes and beans.
    context.scan("io.github.lovexiaopeng.jimu.bootstrap.config");
    // Boot processes auto-configuration after user configuration has defined the DataSource.
    context.register(MybatisPlusAutoConfiguration.class);
    context.registerBean(
        "descriptor",
        io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor.class,
        () -> {
          try {
            return new BuiltinManifestLoader().load();
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
    context.addBeanFactoryPostProcessor(
        factory -> {
          for (String name : factory.getBeanDefinitionNames())
            factory.getBeanDefinition(name).setLazyInit(true);
          // Keep factory signatures/types/qualifiers; replace only database/filesystem boundaries.
          ((AbstractBeanDefinition) factory.getBeanDefinition("ledger"))
              .setInstanceSupplier(
                  () ->
                      new JdbcLedger(
                          factory.getBean(DataSource.class),
                          factory.getBean(ModelProfiles.class),
                          factory.getBean(DataSourceTransactionManager.class),
                          factory.getBean(LedgerUnitOfWork.class),
                          () -> {
                            throw new AssertionError("graph must not query schema");
                          },
                          factory.getBean(SqlSessionTemplate.class)));
          for (String name :
              images ? List.of("artifactStore", "imageStore") : List.of("artifactStore")) {
            ((AbstractBeanDefinition) factory.getBeanDefinition(name))
                .setInstanceSupplier(
                    () ->
                        (ArtifactStore)
                            Proxy.newProxyInstance(
                                ArtifactStore.class.getClassLoader(),
                                new Class<?>[] {ArtifactStore.class},
                                (proxy, method, args) -> {
                                  throw new AssertionError("graph must not access storage");
                                }));
          }
          // Assert production lifecycle metadata before suppressing only scheduler start in this
          // test.
          for (String name :
              role.equals("worker")
                  ? List.of("workerLoop")
                  : List.of("maintenance", "supervisor")) {
            var definition = (AbstractBeanDefinition) factory.getBeanDefinition(name);
            assertEquals("start", definition.getInitMethodName());
            assertEquals("close", definition.getDestroyMethodName());
            definition.setInitMethodName(null);
          }
        });
    context.refresh();
    return context;
  }

  private static void properties(
      AnnotationConfigApplicationContext context, Map<String, Object> values) {
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
  }

  private static String signingKey() {
    return java.util.Base64.getEncoder().encodeToString(new byte[32]);
  }
}
