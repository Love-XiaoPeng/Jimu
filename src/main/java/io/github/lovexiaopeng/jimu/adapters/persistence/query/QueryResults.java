package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;

/** Meters provider rows/columns before a mapper can materialize an unbounded result collection. */
public final class QueryResults {
  private QueryResults() {}

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context)
      throws SQLException {
    return meter(delegate, context, "JDBC");
  }

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context, String statement)
      throws SQLException {
    var state = new State(delegate, context);
    try {
      state.verify(context);
      state.result = context.result(statement);
      state.metadata = delegate.getMetaData();
      return (ResultSet)
          Proxy.newProxyInstance(
              ResultSet.class.getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                  state.close();
                  return null;
                }
                if (method.getName().equals("isClosed") && state.delegate == null) return true;
                try {
                  if (state.owner != null) context.checkHealthy();
                  if (state.delegate == null) throw new SQLException("ResultSet closed");
                  if (method.getName().equals("getMetaData") && state.owner != null)
                    return metadataView(state, context);
                  Object value = method.invoke(state.delegate, args);
                  if (method.getName().equals("next")) {
                    context.remainingMillis();
                    if (Boolean.TRUE.equals(value)) {
                      state.result.row();
                      for (int column = 1; column <= state.metadata.getColumnCount(); column++) {
                        // Include columns a projection ignores; repeated mapped getters cost no
                        // extra
                        // bytes.
                        Object scalar = state.delegate.getObject(column);
                        if (scalar instanceof java.sql.Timestamp time)
                          scalar = time.toLocalDateTime();
                        state.result.column(state.metadata.getColumnLabel(column), scalar);
                      }
                    } else state.result.close();
                  }
                  return value;
                } catch (InvocationTargetException failure) {
                  throw state.failure(failure.getCause());
                } catch (SQLException | RuntimeException failure) {
                  throw state.failure(failure);
                }
              });
    } catch (SQLException | RuntimeException failure) {
      Throwable primary = state.failure(failure);
      try {
        state.close();
      } catch (SQLException | RuntimeException cleanup) {
        suppress(primary, cleanup);
      }
      if (primary instanceof SQLException sql) throw sql;
      throw (RuntimeException) primary;
    }
  }

  private static void suppress(Throwable primary, Throwable extra) {
    if (primary != extra
        && primary.getCause() != extra
        && java.util.Arrays.stream(primary.getSuppressed()).noneMatch(t -> t == extra))
      primary.addSuppressed(extra);
  }

  private static ResultSetMetaData metadataView(State state, SessionQueryContext context) {
    return (ResultSetMetaData)
        Proxy.newProxyInstance(
            ResultSetMetaData.class.getClassLoader(),
            new Class<?>[] {ResultSetMetaData.class},
            (proxy, method, args) -> {
              try {
                context.checkHealthy();
                if (state.metadata == null) throw new SQLException("ResultSet closed");
                return method.invoke(state.metadata, args);
              } catch (InvocationTargetException failure) {
                throw state.failure(failure.getCause());
              } catch (SQLException | RuntimeException failure) {
                throw state.failure(failure);
              }
            });
  }

  /** 游标引用只由本句柄持有；失败登记不阻止close，释放后不再捕获driver/metadata。 */
  private static final class State {
    private ResultSet delegate;
    private ResultSetMetaData metadata;
    private SessionQueryContext.Result result;
    private final SessionQueryWork owner;

    State(ResultSet delegate, SessionQueryContext context) {
      this.delegate = delegate;
      var current = SessionQueryTransaction.currentOwner();
      owner = current != null && current.lineage() ? current : null;
    }

    void verify(SessionQueryContext context) {
      if (owner != null && SessionQueryTransaction.current(context) != owner)
        throw new IllegalStateException("Different query for owned ResultSet");
    }

    Throwable failure(Throwable failure) {
      if (owner == null || failure instanceof Error) return failure;
      var adapted =
          failure instanceof RuntimeException runtime
              ? runtime
              : new DataAccessResourceFailureException("Session query unavailable", failure);
      var first = owner.poisonProof(adapted);
      // 清理故障保留实际SQLException；同一cause不会重复或自引用挂载。
      if (first != adapted) suppress(first, failure);
      return first;
    }

    void close() throws SQLException {
      if (delegate == null) return;
      var cursor = delegate;
      delegate = null;
      metadata = null;
      try {
        cursor.close();
      } catch (SQLException | RuntimeException failure) {
        Throwable primary = failure(failure);
        if (primary instanceof SQLException sql) throw sql;
        throw (RuntimeException) primary;
      } finally {
        if (result != null) result.close();
        result = null;
      }
    }
  }
}
