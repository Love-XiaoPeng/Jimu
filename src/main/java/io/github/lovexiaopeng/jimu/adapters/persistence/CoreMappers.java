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
    io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactMapper artifacts) {
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
            io.github.lovexiaopeng.jimu.adapters.persistence.artifact.mapper.ArtifactMapper.class));
  }
}
