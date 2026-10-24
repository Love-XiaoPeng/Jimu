package io.github.lovexiaopeng.jimu.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import io.github.lovexiaopeng.jimu.bootstrap.config.LedgerPersistenceConfiguration;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 产品 Bean 装配校验，不用不可连接 DS 冒充真实数据库验证。 */
class PersistenceAssemblyTest {
  @Test
  void standaloneQueryFactoryRegistersVisibilityAndRetentionMpWithoutInventingById() {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic");
    var session =
        io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions.create(
            source);
    var configuration = session.getSqlSessionFactory().getConfiguration();
    for (String namespace :
        java.util.List.of(
            "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskVisibilityMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.media.mapper.ImageObjectReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.mcp.mapper.McpCursorMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.TaskDirectoryReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryStepReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryInvocationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryPathReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryDispatchReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionStepReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionInvocationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionBodyReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoStepReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoInvocationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoDispatchReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.todo.mapper.TodoProjectionReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionReadReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionScopeQueryReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionSearchReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryInvocationSessionTraceReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryModelDispatchReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistorySessionPublicEventReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryStepReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryStepSessionScopeReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskSessionScopeReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.HistoryTaskSessionScopeTargetReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryStepReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryCutReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryBindingReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReceiptReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryReservationReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryAttemptReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeQueryNativeReadMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.NativeReadReceiptMapper",
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.ScopeSourceReadMapper")) {
      assertTrue(configuration.hasStatement(namespace + ".selectMaps"));
      assertTrue(configuration.hasStatement(namespace + ".delete"));
      assertFalse(configuration.hasStatement(namespace + ".selectById"));
    }
    assertSame(source, configuration.getEnvironment().getDataSource());
    var priorScan =
        configuration
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionPriorScanMapper.scan")
            .getBoundSql(java.util.Map.of("tenant", "tenant-a", "session", "source", "upper", 127))
            .getSql();
    assertTrue(priorScan.contains("FORCE INDEX(PRIMARY)"));
    assertTrue(priorScan.contains("tenant_id=? AND task_id=? AND seq BETWEEN 0 AND ?"));
    assertTrue(
        priorScan.contains(
            "event_bytes BETWEEN 1 AND 16384 AND OCTET_LENGTH(event_snapshot)=event_bytes ORDER BY seq LIMIT 128"));
    assertFalse(priorScan.contains("SELECT *"));
    assertTrue(
        configuration.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionContextMapper.account"));
    assertTrue(
        configuration.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionScopeReadProofMapper.endpoints"));
    assertTrue(
        configuration.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionReadMapper.commitRead"));
    assertTrue(
        configuration.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionSearchMapper.commitSearch"));
    assertTrue(
        configuration.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionTraceMapper.commitTrace"));
    var projection =
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(
                io.github.lovexiaopeng.jimu.adapters.persistence.directory.entity
                    .DirectoryStepReadEntity.class)
            .getAllSqlSelect();
    System.out.println("DIRECTORY_STEP_MP_PROJECTION=" + projection);
    assertTrue(projection.contains("id AS rowId"));
  }

  @Test
  void defaultApplicationCreatesNoDatabaseOrSqlSession() {
    try (var context =
        new SpringApplicationBuilder(JimuApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--jimu.durable.enabled=false",
                "--jimu.role=worker",
                "--jimu.provider-origin=http://127.0.0.1:1")) {
      assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
      assertTrue(context.getBeansOfType(SqlSessionFactory.class).isEmpty());
      assertTrue(context.getBeansOfType(DataSourceTransactionManager.class).isEmpty());
    }
  }

  @Test
  void mapperAndTransactionUseExactlyTheSameBoundedDataSourceWithoutCaches() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "jimu.durable.enabled",
                      "true",
                      "jimu.db.url",
                      "jdbc:mysql://127.0.0.1:1/unused",
                      "jimu.db.user",
                      "unused",
                      "jimu.db.password",
                      "synthetic")));
      context.register(LedgerPersistenceConfiguration.class, MybatisPlusAutoConfiguration.class);
      context.refresh();
      assertEquals(1, context.getBeansOfType(DataSource.class).size());
      assertEquals(1, context.getBeansOfType(DataSourceTransactionManager.class).size());
      var source = context.getBean(DataSource.class);
      var factory = context.getBean(SqlSessionFactory.class);
      assertSame(source, factory.getConfiguration().getEnvironment().getDataSource());
      assertSame(source, context.getBean(DataSourceTransactionManager.class).getDataSource());
      assertSame(factory, context.getBean(SqlSessionTemplate.class).getSqlSessionFactory());
      assertFalse(factory.getConfiguration().isCacheEnabled());
      assertTrue(factory.getConfiguration().isCallSettersOnNulls());
      assertTrue(factory.getConfiguration().isReturnInstanceForEmptyRow());
      assertEquals(LocalCacheScope.STATEMENT, factory.getConfiguration().getLocalCacheScope());
      assertTrue(
          factory
              .getConfiguration()
              .hasStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper.SchemaVersionMapper.requiredMigrations"));
    }
  }
}
