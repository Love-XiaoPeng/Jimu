package io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionQueryInterceptor;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 独立账本构造入口也使用相同 MP/XML/事务装配；不创建第二个 DataSource。 */
public final class LedgerMapperSessions {
  private LedgerMapperSessions() {}

  public static void configure(Configuration configuration) {
    configuration.setCacheEnabled(false);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setCallSettersOnNulls(true);
    configuration.setReturnInstanceForEmptyRow(true);
    configuration.setDefaultStatementTimeout(5);
  }

  public static SqlSessionTemplate create(DataSource source) {
    return create(source, false);
  }

  /** 受信独立operator的stdout是JSON；MP装配诊断不进入协议输出。 */
  public static SqlSessionTemplate createOperator(DataSource source) {
    return create(source, true);
  }

  private static SqlSessionTemplate create(DataSource source, boolean operator) {
    var configuration = new MybatisConfiguration();
    configure(configuration);
    if (operator) configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
    var factory = new MybatisSqlSessionFactoryBean();
    if (operator) {
      var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
      global.setBanner(false);
      factory.setGlobalConfig(global);
    }
    factory.setDataSource(source);
    factory.setConfiguration(configuration);
    factory.setPlugins(new SessionQueryInterceptor());
    try {
      factory.setMapperLocations(
          new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
      return new SqlSessionTemplate(factory.getObject());
    } catch (Exception failure) {
      throw new IllegalStateException("Ledger Mapper assembly failed", failure);
    }
  }
}
