package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.kernel.operator.service.impl.WebResultAssessmentServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.util.Map;
import javax.sql.DataSource;

/** 原受信入口的同DS装配；业务规则在Service，事务保持只读RR/10秒。 */
public final class WebFetchReconciliation {
  private final DataSource source;

  public WebFetchReconciliation(DataSource source) {
    this.source = source;
  }

  public Map<String, Object> assess(String tenant, String task, String invocation) {
    var session = LedgerMapperSessions.createOperator(source);
    var query =
        new SessionQueryContext(
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10), null);
    return new WebResultAssessmentServiceImpl(
            new MapperWebResultRecords(session, query),
            new OperatorWebResultCodec(),
            new OperatorTransactions(source, true))
        .assess(tenant, task, invocation);
  }
}
