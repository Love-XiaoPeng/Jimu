package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Explicitly scoped transition adapter. All typed/map/empty JDBC reads share result metering. */
public final class SessionJdbcTemplate extends JdbcTemplate {
  private final SessionQueryContext context;

  public SessionJdbcTemplate(DataSource source, SessionQueryContext context) {
    super(source);
    this.context = context;
    setQueryTimeout(5);
  }

  @Override
  public <T> T query(String sql, ResultSetExtractor<T> extractor) {
    return super.query(
        sql,
        (ResultSetExtractor<T>)
            result -> extractor.extractData(QueryResults.meter(result, context)));
  }

  @Override
  public <T> T query(
      PreparedStatementCreator creator,
      PreparedStatementSetter setter,
      ResultSetExtractor<T> extractor) {
    return super.query(
        creator, setter, result -> extractor.extractData(QueryResults.meter(result, context)));
  }

  @Override
  protected void applyStatementSettings(java.sql.Statement statement) throws java.sql.SQLException {
    super.applyStatementSettings(statement);
    QueryStatements.apply(statement, context);
  }
}
