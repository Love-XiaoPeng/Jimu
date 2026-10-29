package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;

/** Mapper query context is an explicit @Param("query") value, never a business ThreadLocal. */
@Intercepts({
  @Signature(
      type = org.apache.ibatis.executor.Executor.class,
      method = "queryCursor",
      args = {
        org.apache.ibatis.mapping.MappedStatement.class,
        Object.class,
        org.apache.ibatis.session.RowBounds.class
      }),
  @Signature(
      type = org.apache.ibatis.executor.Executor.class,
      method = "query",
      args = {
        org.apache.ibatis.mapping.MappedStatement.class,
        Object.class,
        org.apache.ibatis.session.RowBounds.class,
        org.apache.ibatis.session.ResultHandler.class
      }),
  @Signature(
      type = ResultSetHandler.class,
      method = "handleCursorResultSets",
      args = {Statement.class}),
  @Signature(
      type = org.apache.ibatis.executor.Executor.class,
      method = "update",
      args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}),
  @Signature(
      type = StatementHandler.class,
      method = "prepare",
      args = {Connection.class, Integer.class}),
  @Signature(
      type = ResultSetHandler.class,
      method = "handleResultSets",
      args = {Statement.class})
})
public final class SessionQueryInterceptor implements Interceptor {
  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object parameter =
        invocation.getTarget() instanceof org.apache.ibatis.executor.Executor
            ? invocation.getArgs()[1]
            : invocation.getTarget() instanceof StatementHandler statement
                ? statement.getBoundSql().getParameterObject()
                : SystemMetaObject.forObject(invocation.getTarget())
                    .getValue("parameterHandler.parameterObject");
    Object value =
        parameter instanceof Map<?, ?> values
            ? (values.containsKey("query") ? values.get("query") : null)
            : parameter;
    var owner = SessionQueryTransaction.currentOwner();
    if (invocation.getTarget() instanceof org.apache.ibatis.executor.Executor) {
      if (owner != null
          && (!(value instanceof SessionQueryContext context) || !owner.owns(context))) {
        var failure = new IllegalStateException("Session write requires its owning query");
        owner.poisonProof(failure);
        throw failure;
      }
    }
    if (!(value instanceof SessionQueryContext context)) return invocation.proceed();
    if (invocation.getTarget() instanceof ResultSetHandler) {
      // JDBC已执行，流式ResultSet可能已打开；截止拒绝也必须在清理保护内。
      var statement = (Statement) invocation.getArgs()[0];
      try {
        context.remainingMillis();
        return results(invocation, context, parameter);
      } catch (Throwable failure) {
        // MyBatis后续会丢弃反射包装；清理cause必须挂在真正向外传播的原异常上。
        Throwable primary = org.apache.ibatis.reflection.ExceptionUtil.unwrapThrowable(failure);
        closeFailed(statement, primary);
        throw primary;
      }
    }
    context.remainingMillis();
    if (invocation.getTarget() instanceof org.apache.ibatis.executor.Executor) {
      var command = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
      if (owner == null) return invocation.proceed();
      try {
        if (command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT)
          SessionDependencyWrites.before(command.getId(), parameter, owner);
        Object result = invocation.proceed();
        SessionDependencyWrites.observed(command.getId(), parameter, result, owner);
        return result;
      } catch (Throwable failure) {
        owner.poisonProof(
            failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Session dependency write unconfirmed", failure));
        throw failure;
      }
    }
    if (invocation.getTarget() instanceof StatementHandler handler) {
      var statement = (Statement) invocation.proceed();
      try {
        QueryStatements.apply(statement, context);
        var history = owner == null ? null : owner.pendingHistory();
        if (history != null) {
          context.operation(5); // reflected command lookup and dispatch
          var command =
              (org.apache.ibatis.mapping.MappedStatement)
                  SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
          history.prepared(
              command.getId(), parameter, (Connection) invocation.getArgs()[0], statement);
        }
        if (owner != null && owner.eventHistoryPending()) {
          var command =
              (org.apache.ibatis.mapping.MappedStatement)
                  SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
          var length = owner.eventHistoryLength(command.getId());
          if (length != null)
            length.prepared(
                command.getId(), parameter, (Connection) invocation.getArgs()[0], statement);
        }
        return statement;
      } catch (Throwable failure) {
        closeFailed(statement, failure);
        throw failure;
      }
    }
    return invocation.proceed();
  }

  private static void closeFailed(Statement statement, Throwable failure) {
    try {
      statement.close();
    } catch (Throwable cleanup) {
      if (cleanup != failure) failure.addSuppressed(cleanup);
    }
  }

  private static Object results(
      Invocation invocation, SessionQueryContext context, Object parameter) throws Throwable {
    var original = (Statement) invocation.getArgs()[0];
    var results = new java.util.IdentityHashMap<java.sql.ResultSet, java.sql.ResultSet>();
    invocation.getArgs()[0] =
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
              try {
                Object result = method.invoke(original, args);
                if (method.getName().equals("getResultSet")
                    && result instanceof java.sql.ResultSet rows) {
                  if (!results.containsKey(rows)) {
                    String statement =
                        ((org.apache.ibatis.mapping.MappedStatement)
                                SystemMetaObject.forObject(invocation.getTarget())
                                    .getValue("mappedStatement"))
                            .getId();
                    var owner = SessionQueryTransaction.currentOwner();
                    var history = owner == null ? null : owner.pendingHistory();
                    if (history != null) {
                      context.operation(2); // distinct history route and ResultSet identity put
                      results.put(
                          rows,
                          QueryResults.meterHistory(
                              rows, context, statement, history, parameter, original));
                      return results.get(rows);
                    }
                    var length = owner == null ? null : owner.eventHistoryLength(statement);
                    results.put(
                        rows,
                        QueryResults.meter(rows, context, statement, length, parameter, original));
                  }
                  return results.get(rows);
                }
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
    return invocation.proceed();
  }
}
