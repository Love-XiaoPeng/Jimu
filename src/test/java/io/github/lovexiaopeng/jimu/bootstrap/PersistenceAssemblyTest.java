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
