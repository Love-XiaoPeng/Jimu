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
    if (!(value instanceof SessionQueryContext context)) return invocation.proceed();
    context.remainingMillis();
    if (invocation.getTarget() instanceof org.apache.ibatis.executor.Executor) {
      var command = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
      var owner = SessionQueryTransaction.current(context);
      if (owner == null) return invocation.proceed();
      String sql = command.getBoundSql(parameter).getSql().toLowerCase(java.util.Locale.ROOT);
      if (dependencyWrite(sql)) owner.dependencyMutation(dependencyKeys(parameter));
      else owner.mutation();
      try {
        return invocation.proceed();
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

  // 只检查真正写入的SET/列区域，WHERE中的当前fence/权限条件不是依赖变更。
  static boolean dependencyWrite(String sql) {
    String normalized = sql.replaceAll("\\s+", " ").trim();
    if (normalized.matches(
        "(?s)^(insert into|update|delete from) (actor_access|installation)( |\\().*")) return true;
    if (!normalized.matches(
        "(?s)^(insert into|update|delete from) `?(task|task_session_history|task_session_scope|task_session_scope_target|task_session_lineage)`?( |\\().*"))
      return false;
    int where = normalized.indexOf(" where ");
    String write = where < 0 ? normalized : normalized.substring(0, where);
    return write.matches(
            "(?s).*\\b(owner_actor_id|capabilities|tools_digest|installation_epoch|request_digest|request_snapshot|binding_digest|binding_snapshot|retain_until|grant_state|content_state|authority_snapshot|genesis_snapshot|graph_snapshot|revoked_at|invalidated_at|target_snapshot|source_task_id)\\b.*")
        || normalized.startsWith("delete ");
  }

  static java.util.Set<SessionQueryWork.Dependency> dependencyKeys(Object parameter) {
    if (!(parameter instanceof Map<?, ?> values)) return null;
    Object tenant = values.get("tenant");
    if (tenant == null) tenant = values.get("tenantId");
    Object task = null;
    for (String key : java.util.List.of("caller", "taskId", "task", "id"))
      if (values.get(key) instanceof String text) {
        task = text;
        break;
      }
    return tenant instanceof String t && task instanceof String id
        ? java.util.Set.of(new SessionQueryWork.Dependency(t, id))
        : null;
  }
}
