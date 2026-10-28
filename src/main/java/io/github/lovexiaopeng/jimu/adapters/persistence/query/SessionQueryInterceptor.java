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
        invocation.getTarget() instanceof StatementHandler statement
            ? statement.getBoundSql().getParameterObject()
            : SystemMetaObject.forObject(invocation.getTarget())
                .getValue("parameterHandler.parameterObject");
    Object value =
        parameter instanceof Map<?, ?> values
            ? (values.containsKey("query") ? values.get("query") : null)
            : parameter;
    if (!(value instanceof SessionQueryContext context)) return invocation.proceed();
    context.remainingMillis();
    if (invocation.getTarget() instanceof StatementHandler handler) {
      var command =
          (org.apache.ibatis.mapping.MappedStatement)
              SystemMetaObject.forObject(handler).getValue("delegate.mappedStatement");
      if (command.getSqlCommandType() != org.apache.ibatis.mapping.SqlCommandType.SELECT) {
        var owner = SessionQueryTransaction.current(context);
        if (owner != null) owner.mutation();
      }
      var statement = (Statement) invocation.proceed();
      QueryStatements.apply(statement, context);
      return statement;
    }
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
                  if (!results.containsKey(rows))
                    results.put(
                        rows,
                        QueryResults.meter(
                            rows,
                            context,
                            ((org.apache.ibatis.mapping.MappedStatement)
                                    SystemMetaObject.forObject(invocation.getTarget())
                                        .getValue("mappedStatement"))
                                .getId()));
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
