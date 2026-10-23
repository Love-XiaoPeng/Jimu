package io.github.lovexiaopeng.jimu.bootstrap.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerSchema;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper.SchemaVersionMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.transaction.SpringLedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionQueryDeadline;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** durable 专用装配：唯一有界 DataSource，同一 Spring 事务与 SqlSession。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
@MapperScan(
    basePackages = "io.github.lovexiaopeng.jimu.adapters.persistence",
    annotationClass = Mapper.class)
public class LedgerPersistenceConfiguration {
  @Bean
  io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryInterceptor
      sessionQueryInterceptor() {
    return new io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryInterceptor();
  }

  @Bean
  DataSource ledgerDataSource(Environment env) {
    return SessionQueryDeadline.source(
        new DriverManagerDataSource(
            env.getRequiredProperty("jimu.db.url"),
            env.getRequiredProperty("jimu.db.user"),
            env.getRequiredProperty("jimu.db.password")));
  }

  @Bean
  DataSourceTransactionManager ledgerTransactionManager(DataSource source) {
    return new DataSourceTransactionManager(source);
  }

  @Bean
  LedgerUnitOfWork ledgerUnitOfWork(DataSourceTransactionManager manager) {
    return new SpringLedgerUnitOfWork(manager);
  }

  @Bean
  ConfigurationCustomizer ledgerMybatisConfiguration() {
    return io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions
        ::configure;
  }

  @Bean
  MybatisPlusPropertiesCustomizer ledgerMapperLocations() {
    return properties -> properties.setMapperLocations(new String[] {"classpath*:mapper/**/*.xml"});
  }

  @Bean
  LedgerSchema ledgerSchema(SchemaVersionMapper mapper) {
    return new LedgerSchema(mapper);
  }
}
