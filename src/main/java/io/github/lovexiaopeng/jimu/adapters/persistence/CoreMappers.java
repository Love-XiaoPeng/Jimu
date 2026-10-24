package io.github.lovexiaopeng.jimu.adapters.persistence;

import org.mybatis.spring.SqlSessionTemplate;

/** 同一 SqlSessionTemplate 的具名 Mapper 集合；不是第二套存储或任意 SQL 入口。 */
record CoreMappers(
    SqlSessionTemplate session,
    io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper tasks,
    io.github.lovexiaopeng.jimu.adapters.persistence.worker.mapper.WorkerProcessMapper workers,
    io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper budgets,
    io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptMapper attempts,
    io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.EvaluationBudgetMapper
        evaluations,
    io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper reservations,
    io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper invocations,
    io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper.EventMapper events,
    io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper steps,
    io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactMapper artifacts,
    io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.ModelDispatchMapper dispatches) {
  // MP insert 的映射以实体字段为根参数；显式 query 同级传入，复用已有截止/实计插件。
  // 固定两个 statement ID；不接收外部 SQL、表名或 namespace。
  int insertDispatch(
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      io.github.lovexiaopeng.jimu.adapters.persistence.model.entity.ModelDispatchEntity entity) {
    return session.insert(
        io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.ModelDispatchMapper.class
                .getName()
            + ".insert",
        writeParameters(entity, query));
  }

  int insertInvocation(
      io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query,
      io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity.InvocationEntity entity) {
    return session.insert(
        io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper.class
                .getName()
            + ".insert",
        writeParameters(entity, query));
  }

  private static java.util.Map<String, Object> writeParameters(
      Object entity, io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext query) {
    var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
    var parameters = new java.util.HashMap<String, Object>();
    for (String property : meta.getGetterNames()) parameters.put(property, meta.getValue(property));
    parameters.put("query", query);
    return parameters;
  }

  static CoreMappers from(SqlSessionTemplate session) {
    return new CoreMappers(
        session,
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.worker.mapper.WorkerProcessMapper
                .class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.TenantBudgetMapper
                .class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.TaskAttemptMapper
                .class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.EvaluationBudgetMapper
                .class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.budget.mapper.ReservationMapper.class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper
                .class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.event.mapper.EventMapper.class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactMapper.class),
        session.getMapper(
            io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper.ModelDispatchMapper
                .class));
  }
}
