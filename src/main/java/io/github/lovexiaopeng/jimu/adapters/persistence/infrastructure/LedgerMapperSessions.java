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
    var configuration = new MybatisConfiguration();
    configure(configuration);
    var factory = new MybatisSqlSessionFactoryBean();
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
