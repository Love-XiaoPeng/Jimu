package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Meters provider rows/columns before a mapper can materialize an unbounded result collection. */
public final class QueryResults {
  private QueryResults() {}

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context)
      throws SQLException {
    return meter(delegate, context, "JDBC");
  }

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context, String statement)
      throws SQLException {
    var result = context.result(statement);
    var metadata = delegate.getMetaData();
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              try {
                Object value = method.invoke(delegate, args);
                if (method.getName().equals("next")) {
                  context.remainingMillis();
                  if (Boolean.TRUE.equals(value)) {
                    result.row();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                      // Include columns a projection ignores; repeated mapped getters cost no extra
                      // bytes.
                      Object scalar = delegate.getObject(column);
                      if (scalar instanceof java.sql.Timestamp time)
                        scalar = time.toLocalDateTime();
                      result.column(metadata.getColumnLabel(column), scalar);
                    }
                  }
                }
                return value;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
