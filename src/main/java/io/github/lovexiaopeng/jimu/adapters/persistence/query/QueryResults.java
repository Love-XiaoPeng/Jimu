package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;

/** Meters provider rows/columns before a mapper can materialize an unbounded result collection. */
public final class QueryResults {
  private QueryResults() {}

  /** nodeInput三份当前raw的同步消费者；不持有Result或跨行资格。 */
  public interface NodeInputRaw {
    void read(SessionQueryContext.Result result, String label, String raw);
  }

  public static void verifyNodeInputCommand(
      org.apache.ibatis.mapping.MappedStatement command, SessionQueryContext query, Object args) {
    SessionLineageSourceColumns.verifyNodeInputCommand(command, query, args);
  }

  /** Internal synchronous FINAL handler; the actual SQL result never leaves read(). */
  public interface FinalMetadata extends org.apache.ibatis.session.ResultHandler<Object> {
    void read(
        ResultSet rows,
        SessionQueryContext context,
        org.apache.ibatis.mapping.MappedStatement command)
        throws SQLException;
  }

  /** 当前Result的完整列投影；Connector原字段只在本次同步调用借用，不授予跨行资格。 */
  public static void finalColumns(
      ResultSetMetaData metadata,
      java.util.List<? extends SessionQueryContext.MetadataLabel> columns,
      SessionQueryContext query,
      SessionQueryWork owner,
      long version)
      throws SQLException {
    query.operation(5); // native-class route, borrowed fields/local and width comparison
    if (metadata.getClass() == com.mysql.cj.jdbc.result.ResultSetMetaData.class) {
      var fields = ((com.mysql.cj.jdbc.result.ResultSetMetaData) metadata).getFields();
      finalBoundary(query, owner, version);
      if (fields.length != columns.size()) throw corruptFinalColumns();
      // Exact Connector Field getters read its current projection data. getColumnLabel
      // delegates to getName under both alias modes; YEAR is rejected by every original kind.
      // A driver wrapper/subclass retains the original individually guarded callback path below.
      int i = 0;
      for (; i < fields.length; i++) {
        var column = columns.get(i);
        query.operation(8L + column.name().length()); // one loop/field load, class/label/type gates
        var field = fields[i];
        if (field == null || field.getClass() != com.mysql.cj.result.Field.class) break;
        if (!column.name().equals(field.getName()) || !jdbcType(column.kind(), field.getJavaType()))
          throw corruptFinalColumns();
      }
      if (i == fields.length) return;
    }
    int count = metadata.getColumnCount();
    finalBoundary(query, owner, version);
    if (count != columns.size()) throw corruptFinalColumns();
    for (int i = 0; i < columns.size(); i++) {
      var column = columns.get(i);
      query.operation(9L + column.name().length());
      String label = metadata.getColumnLabel(i + 1);
      finalBoundary(query, owner, version);
      int type = metadata.getColumnType(i + 1);
      finalBoundary(query, owner, version);
      if (!column.name().equals(label) || !jdbcType(column.kind(), type))
        throw corruptFinalColumns();
    }
  }

  private static io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure corruptFinalColumns() {
    return new io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure(
        409, "SESSION_HISTORY_CORRUPT");
  }

  private static void finalBoundary(
      SessionQueryContext query, SessionQueryWork owner, long version) {
    query.operation(1);
    if (owner != null) owner.metadataFinalBoundary(query, version);
    else query.checkHealthy();
  }

  private static boolean jdbcType(char kind, int type) {
    return switch (kind) {
      case 'S' ->
          type == java.sql.Types.VARCHAR
              || type == java.sql.Types.CHAR
              || type == java.sql.Types.LONGVARCHAR;
      case 'I' -> type == java.sql.Types.INTEGER;
      case 'L' -> type == java.sql.Types.BIGINT;
      case 'B' ->
          type == java.sql.Types.BIT
              || type == java.sql.Types.BOOLEAN
              || type == java.sql.Types.TINYINT;
      case 'D' -> type == java.sql.Types.TIMESTAMP;
      case 'T', 'M', 'X' -> type == java.sql.Types.INTEGER || type == java.sql.Types.BIGINT;
      default -> false;
    };
  }

  public static void verifyModelStepCommand(
      org.apache.ibatis.mapping.MappedStatement command, SessionQueryContext query, Object args) {
    SessionLineageSourceColumns.verifyModelStepCommand(command, query, args);
  }

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context)
      throws SQLException {
    return meter(delegate, context, "JDBC");
  }

  public static ResultSet meter(ResultSet delegate, SessionQueryContext context, String statement)
      throws SQLException {
    return meter(delegate, context, statement, null);
  }

  private static ResultSet meter(
      ResultSet delegate, SessionQueryContext context, String statement, Object parameter)
      throws SQLException {
    boolean modelTask = SessionLineageSourceColumns.modelTask(statement);
    if (modelTask) {
      context.operation(8); // route, width, reservation, row bookkeeping and close alias release
      context.retainScratch(delegate, 128);
    }
    var state = new State(delegate, context);
    state.taskQuery = modelTask ? context : null;
    state.taskWidth = modelTask ? SessionLineageSourceColumns.modelTaskWidth(statement) : 0;
    var step =
        parameter instanceof Map<?, ?> args && args.containsKey("modelStepHash")
            ? args.get("modelStepHash")
            : null;
    if (step != null && !(step instanceof java.util.function.BiConsumer<?, ?>))
      throw new IllegalStateException("Invalid model step consumer");
    @SuppressWarnings("unchecked")
    var stepHash = (java.util.function.BiConsumer<SessionQueryContext.Result, String>) step;
    var nodeRaw =
        parameter instanceof Map<?, ?> args && args.containsKey("nodeInputRaw")
            ? (NodeInputRaw) args.get("nodeInputRaw")
            : null;
    boolean synchronousRaw = stepHash != null || nodeRaw != null || modelTask;
    if (modelTask) context.operation(2); // task route and guarded handoff
    try {
      state.verify(context);
      if (synchronousRaw && state.owner == null)
        throw new IllegalStateException("Unowned step result");
      final long stepVersion = !synchronousRaw ? 0 : state.owner.metadataFinalVersion(context);
      state.result = context.result(statement);
      if (modelTask) checkStep(state, context, stepVersion);
      state.metadata = delegate.getMetaData();
      if (modelTask) checkStep(state, context, stepVersion);
      if (state.owner != null) {
        context.operation(1);
        if (state.owner.lineageSourceActive() && nodeRaw == null)
          SessionLineageSourceColumns.verify(delegate, context, statement, state.owner);
      }
      return (ResultSet)
          Proxy.newProxyInstance(
              ResultSet.class.getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                  if (modelTask) {
                    Throwable primary = null;
                    try {
                      checkStep(state, context, stepVersion);
                    } catch (RuntimeException | Error failure) {
                      primary = state.failure(failure);
                    }
                    try {
                      state.close();
                      checkStep(state, context, stepVersion);
                      state.completeTaskRow(context);
                      context.operation(1);
                      if (!state.taskEnded)
                        throw new IllegalStateException("Unfinished task result");
                    } catch (SQLException | RuntimeException | Error failure) {
                      if (primary == null) primary = state.failure(failure);
                      else suppress(primary, failure);
                    }
                    if (primary != null) throw primary;
                    return null;
                  }
                  try {
                    state.close();
                    if (synchronousRaw) checkStep(state, context, stepVersion);
                    return null;
                  } catch (RuntimeException failure) {
                    if (state.owner != null) throw state.owner.poisonProof(failure);
                    throw failure;
                  } catch (Error failure) {
                    if (state.owner != null) state.owner.poisonLineageSource(failure);
                    throw failure;
                  }
                }
                if (method.getName().equals("isClosed") && state.delegate == null) return true;
                try {
                  if (state.owner != null) context.checkHealthy();
                  if (state.delegate == null) throw new SQLException("ResultSet closed");
                  if (method.getName().equals("getMetaData") && state.owner != null)
                    return metadataView(state, context, modelTask, stepVersion);
                  if (synchronousRaw) checkStep(state, context, stepVersion);
                  if (modelTask && method.getName().equals("next")) state.completeTaskRow(context);
                  Object value = method.invoke(state.delegate, args);
                  if (synchronousRaw) checkStep(state, context, stepVersion);
                  if (modelTask
                      && state.taskColumn >= 0
                      && (method.getName().startsWith("get")
                          || method.getName().equals("wasNull"))) {
                    context.operation(
                        11); // getter/NULL route, width/label, ordinal and pending scalar
                    if (method.getName().equals("wasNull")) {
                      if (state.taskZero == null)
                        throw new IllegalStateException("Unexpected task NULL callback");
                      Object scalar = Boolean.TRUE.equals(value) ? null : state.taskZero;
                      state.taskZero = null;
                      state.taskColumn(scalar);
                    } else if (args != null
                        && (args.length == 1
                            || (args.length == 2
                                && method.getName().equals("getObject")
                                && args[1] == java.time.LocalDateTime.class))
                        && (method.getName().equals("getObject")
                            || method.getName().equals("getString")
                            || method.getName().equals("getLong")
                            || method.getName().equals("getInt")
                            || method.getName().equals("getTimestamp"))) {
                      if (state.taskZero != null || state.taskColumn >= state.taskWidth)
                        throw new IllegalStateException("Repeated or incomplete task getter");
                      String label =
                          SessionLineageSourceColumns.modelTaskLabel(
                              state.taskColumn, state.taskWidth);
                      boolean nativeName = false;
                      context.operation(8); // exact native metadata/Field and current name identity
                      if (args[0] instanceof String
                          && state.metadata.getClass()
                              == com.mysql.cj.jdbc.result.ResultSetMetaData.class) {
                        var field =
                            ((com.mysql.cj.jdbc.result.ResultSetMetaData) state.metadata)
                                .getFields()[state.taskColumn];
                        nativeName =
                            field != null
                                && field.getClass() == com.mysql.cj.result.Field.class
                                && args[0] == field.getName();
                      }
                      if (!nativeName) {
                        context.operation(4L + label.length());
                        if (!(label.equals(args[0])
                            || Integer.valueOf(state.taskColumn + 1).equals(args[0])))
                          throw new IllegalStateException("Changed task getter order: " + label);
                      }
                      if ((method.getName().equals("getLong") || method.getName().equals("getInt"))
                          && ((Number) value).longValue() == 0) state.taskZero = value;
                      else state.taskColumn(value, label);
                    }
                    context.operation(1);
                    if (state.result.hasColumnCallback())
                      checkStep(state, context, stepVersion); // independent external meter boundary
                  }
                  if (method.getName().equals("next")) {
                    context.remainingMillis();
                    if (Boolean.TRUE.equals(value)) {
                      if (modelTask)
                        SessionLineageSourceColumns.verifyModelTask(
                            state.delegate,
                            context,
                            state.taskWidth,
                            parameter,
                            state.owner,
                            stepVersion);
                      if (nodeRaw != null) {
                        SessionLineageSourceColumns.verifyNodeInput(
                            state.delegate,
                            context,
                            statement,
                            parameter,
                            state.owner,
                            stepVersion);
                        checkStep(state, context, stepVersion);
                      }
                      if (stepHash != null)
                        SessionLineageSourceColumns.verifyModelStep(
                            state.delegate,
                            context,
                            statement,
                            parameter,
                            state.owner,
                            stepVersion);
                      state.result.row();
                      if (modelTask) {
                        context.operation(2); // new row, all source columns already checked
                        state.taskColumn = 0;
                        return value;
                      }
                      int columns = state.metadata.getColumnCount();
                      if (modelTask) checkStep(state, context, stepVersion);
                      for (int column = 1; column <= columns; column++) {
                        // Include columns a projection ignores; repeated mapped getters cost no
                        // extra
                        // bytes.
                        if (synchronousRaw) checkStep(state, context, stepVersion);
                        Object scalar = state.delegate.getObject(column);
                        if (synchronousRaw) checkStep(state, context, stepVersion);
                        if (scalar instanceof java.sql.Timestamp time)
                          scalar = time.toLocalDateTime();
                        String label = state.metadata.getColumnLabel(column);
                        if (modelTask) checkStep(state, context, stepVersion);
                        if (nodeRaw != null
                            && (label.equals("request_snapshot")
                                || label.equals("genesis_snapshot")
                                || label.equals("graph_snapshot"))) {
                          if (!(scalar instanceof String raw))
                            throw new IllegalStateException("Missing node input raw");
                          nodeRaw.read(state.result, label, raw);
                          state.verify(context);
                          context.checkHealthy();
                        } else if (stepHash != null && label.equals("request_snapshot")) {
                          if (!(scalar instanceof String raw))
                            throw new IllegalStateException("Missing model step raw");
                          stepHash.accept(state.result, raw);
                          state.verify(context);
                          context.checkHealthy();
                        } else state.result.column(label, scalar);
                        if (synchronousRaw) checkStep(state, context, stepVersion);
                      }
                    } else {
                      if (modelTask) {
                        context.operation(1);
                        state.taskEnded = true;
                      }
                      state.result.close();
                    }
                  }
                  return value;
                } catch (InvocationTargetException failure) {
                  throw state.failure(failure.getCause());
                } catch (SQLException | RuntimeException failure) {
                  throw state.failure(failure);
                } catch (Error failure) {
                  state.fatal = failure;
                  if (state.owner != null) state.owner.poisonLineageSource(failure);
                  throw failure;
                }
              });
    } catch (SQLException | RuntimeException | Error failure) {
      if (failure instanceof Error fatal && state.owner != null)
        state.owner.poisonLineageSource(fatal);
      Throwable primary = state.failure(failure);
      try {
        state.close();
      } catch (SQLException | RuntimeException | Error cleanup) {
        suppress(primary, cleanup);
      }
      if (primary instanceof SQLException sql) throw sql;
      if (primary instanceof Error fatal) throw fatal;
      throw (RuntimeException) primary;
    }
  }

  private static void checkStep(State state, SessionQueryContext context, long generation) {
    // One current Work lookup at this instant checks query, identity, health and generation.
    // This boundary is still called independently before and after every external callback.
    state.owner.metadataFinalBoundary(context, generation);
  }

  static ResultSet meter(
      ResultSet delegate,
      SessionQueryContext context,
      String statement,
      SessionEventHistoryRows.Slot length,
      Object parameter,
      java.sql.Statement prepared)
      throws SQLException {
    context.operation(1);
    if (length == null) return meter(delegate, context, statement, parameter);
    return meterEventHistory(delegate, context, statement, length, parameter, prepared);
  }

  private static void suppress(Throwable primary, Throwable extra) {
    if (primary != extra
        && primary.getCause() != extra
        && extra.getCause() != primary
        && java.util.Arrays.stream(primary.getSuppressed()).noneMatch(t -> t == extra))
      primary.addSuppressed(extra);
  }

  private static ResultSetMetaData metadataView(
      State state, SessionQueryContext context, boolean guarded, long version) {
    return (ResultSetMetaData)
        Proxy.newProxyInstance(
            ResultSetMetaData.class.getClassLoader(),
            new Class<?>[] {ResultSetMetaData.class},
            (proxy, method, args) -> {
              try {
                context.checkHealthy();
                if (state.metadata == null) throw new SQLException("ResultSet closed");
                if (guarded) checkStep(state, context, version);
                Object value = method.invoke(state.metadata, args);
                if (guarded) checkStep(state, context, version);
                return value;
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
    private Error fatal;
    private SessionQueryContext taskQuery;
    private int taskColumn = -1;
    private int taskWidth;
    private Object taskZero;
    private boolean taskEnded;
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

    void taskColumn(Object scalar) {
      taskQuery.operation(3); // pending zero/NULL has no getter-local label
      taskColumn(scalar, SessionLineageSourceColumns.modelTaskLabel(taskColumn, taskWidth));
    }

    void taskColumn(Object scalar, String label) {
      taskQuery.operation(5); // normalization, meter, ordinal and alias release
      if (scalar instanceof java.sql.Timestamp time) scalar = time.toLocalDateTime();
      result.column(label, scalar);
      taskColumn++;
    }

    void completeTaskRow(SessionQueryContext context) {
      context.operation(3);
      if ((taskColumn >= 0 && taskColumn != taskWidth) || taskZero != null)
        throw new IllegalStateException("Incomplete task row consumption");
    }

    Throwable failure(Throwable failure) {
      if (fatal != null && fatal != failure) {
        suppress(fatal, failure);
        return fatal;
      }
      if (failure instanceof Error error) {
        fatal = error;
        if (owner != null) owner.poisonLineageSource(error);
        return error;
      }
      if (owner == null) return failure;
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
      } catch (SQLException | RuntimeException | Error failure) {
        Throwable primary = failure(failure);
        if (primary instanceof SQLException sql) throw sql;
        if (primary instanceof Error error) throw error;
        throw (RuntimeException) primary;
      } finally {
        taskZero = null;
        var payer = taskQuery;
        taskQuery = null;
        if (payer != null) payer.releaseScratch(cursor);
        if (result != null) result.close();
        result = null;
      }
    }
  }

  private static ResultSet meterEventHistory(
      ResultSet delegate,
      SessionQueryContext context,
      String statement,
      SessionEventHistoryRows.Slot length,
      Object parameter,
      java.sql.Statement prepared)
      throws SQLException {
    var state = new EventHistoryState(delegate, context);
    state.length = length;
    state.size = SessionEventHistoryRows.SIZE.equals(statement);
    try {
      state.verify(context);
      state.result =
          state.size ? context.sqlUtf8LengthResult(statement) : context.result(statement);
      state.metadata = delegate.getMetaData();
      if (length != null) length.bind(statement, parameter, prepared, delegate, state.result);
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
                    return eventHistoryMetadataView(state, context);
                  Object value = method.invoke(state.delegate, args);
                  if (method.getName().equals("next")) {
                    context.remainingMillis();
                    if (state.length != null)
                      state.length.next(state.size, Boolean.TRUE.equals(value));
                    if (Boolean.TRUE.equals(value)) {
                      state.result.row();
                      for (int column = 1; column <= state.metadata.getColumnCount(); column++) {
                        // Include columns a projection ignores; repeated mapped getters cost no
                        // extra
                        // bytes.
                        Object scalar = state.delegate.getObject(column);
                        if (scalar instanceof java.sql.Timestamp time)
                          scalar = time.toLocalDateTime();
                        String label = state.metadata.getColumnLabel(column);
                        context.operation(2);
                        if (state.length != null && !state.size)
                          state.length.column(state.result, column, label, scalar);
                        else state.result.column(label, scalar);
                      }
                      if (state.length != null && state.size) state.length.size(state.result);
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

  private static ResultSetMetaData eventHistoryMetadataView(
      EventHistoryState state, SessionQueryContext context) {
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
  private static final class EventHistoryState {
    private ResultSet delegate;
    private ResultSetMetaData metadata;
    private SessionQueryContext.Result result;
    private SessionEventHistoryRows.Slot length;
    private boolean size;
    private final SessionQueryWork owner;

    EventHistoryState(ResultSet delegate, SessionQueryContext context) {
      this.delegate = delegate;
      var current = SessionQueryTransaction.currentOwner();
      owner = current != null && current.lineage() ? current : null;
    }

    void verify(SessionQueryContext context) {
      if (owner != null && SessionQueryTransaction.current(context) != owner)
        throw new IllegalStateException("Different query for owned ResultSet");
    }

    Throwable failure(Throwable failure) {
      if (length != null) length.invalidate();
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
        if (length != null) length.closed(size);
        length = null;
      }
    }
  }

  static ResultSet meterHistory(
      ResultSet delegate,
      SessionQueryContext context,
      String statement,
      SessionHistoryRows.Slot length,
      Object parameter,
      java.sql.Statement prepared)
      throws SQLException {
    // State/proxy allocation, route/lifecycle stores and two unconditional close stores:
    // cached metadata view and local cursor alias, even if no metadata view is requested.
    context.operation(10);
    var state = new HistoryState(delegate, context);
    state.length = length;
    state.size = length.sizeSource();
    try {
      state.verify(context);
      state.result =
          state.size ? context.sqlUtf8LengthResult(statement) : context.result(statement);
      state.metadata = delegate.getMetaData();
      if (length != null) length.bind(statement, parameter, prepared, delegate);
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
                    return historyMetadataView(state, context);
                  Object value = method.invoke(state.delegate, args);
                  if (method.getName().equals("next")) {
                    context.remainingMillis();
                    if (state.length != null)
                      state.length.next(state.size, Boolean.TRUE.equals(value));
                    if (Boolean.TRUE.equals(value)) {
                      state.result.row();
                      for (int column = 1; column <= state.metadata.getColumnCount(); column++) {
                        // Include columns a projection ignores; repeated mapped getters cost no
                        // extra
                        // bytes.
                        Object scalar = state.delegate.getObject(column);
                        if (scalar instanceof java.sql.Timestamp time)
                          scalar = time.toLocalDateTime();
                        String label = state.metadata.getColumnLabel(column);
                        context.operation(2);
                        if (state.length != null && !state.size)
                          state.length.column(state.result, column, label, scalar);
                        else state.result.column(label, scalar);
                      }
                      if (state.length != null && state.size) state.length.size(state.result);
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

  private static ResultSetMetaData historyMetadataView(
      HistoryState state, SessionQueryContext context) {
    context.checkHealthy();
    context.operation(3); // cached field lookup, null test and view handoff on every access
    if (state.metadataView == null) {
      // Loader lookup; interface array/allocation+store; handler/allocation+two captures;
      // proxy allocation; field publication. Cleanup is prepaid at cursor construction.
      // At most two views (size/raw) fit the existing per-slot 4096-byte object inventory.
      context.operation(8);
      state.metadataView =
          (ResultSetMetaData)
              Proxy.newProxyInstance(
                  ResultSetMetaData.class.getClassLoader(),
                  new Class<?>[] {ResultSetMetaData.class},
                  (proxy, method, args) -> {
                    try {
                      context.checkHealthy();
                      context.operation(
                          4); // current metadata lookup, null test, invoke and handoff
                      if (state.metadata == null) throw new SQLException("ResultSet closed");
                      return method.invoke(state.metadata, args);
                    } catch (InvocationTargetException failure) {
                      throw state.failure(failure.getCause());
                    } catch (SQLException | RuntimeException failure) {
                      throw state.failure(failure);
                    }
                  });
    }
    return state.metadataView;
  }

  /** 游标引用只由本句柄持有；失败登记不阻止close，释放后不再捕获driver/metadata。 */
  private static final class HistoryState {
    private ResultSet delegate;
    private ResultSetMetaData metadata;
    private ResultSetMetaData metadataView;
    private SessionQueryContext.Result result;
    private SessionHistoryRows.Slot length;
    private boolean size;
    private final SessionQueryWork owner;

    HistoryState(ResultSet delegate, SessionQueryContext context) {
      this.delegate = delegate;
      var current = SessionQueryTransaction.currentOwner();
      owner = current != null && current.lineage() ? current : null;
    }

    void verify(SessionQueryContext context) {
      if (owner != null && SessionQueryTransaction.current(context) != owner)
        throw new IllegalStateException("Different query for owned ResultSet");
    }

    Throwable failure(Throwable failure) {
      if (length != null) length.invalidate();
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
      metadataView = null;
      try {
        cursor.close();
      } catch (SQLException | RuntimeException failure) {
        Throwable primary = failure(failure);
        if (primary instanceof SQLException sql) throw sql;
        throw (RuntimeException) primary;
      } finally {
        cursor = null;
        if (result != null) result.close();
        result = null;
        if (length != null) length.closed(size);
        length = null;
      }
    }
  }
}
