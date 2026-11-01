package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionPreparedScopeEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionPreparedScopeMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCheckpointPreparation;
import java.util.Map;

/** 原 codec 与惰性字段访问；真实 MP 更新/插入复用原 DS、query 和工作单元。 */
final class MapperSessionCheckpointPreparation implements SessionCheckpointPreparation {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final String step;

  MapperSessionCheckpointPreparation(
      JdbcSessionRecords records, Map<String, Object> task, String step) {
    this.records = records;
    this.task = task;
    this.step = step;
  }

  public Checkpoint decode(String request) {
    var wire = Payloads.read(request, DurableAgent.Checkpoint.class);
    return new Checkpoint() {
      public String requestBody() {
        return wire.requestBody();
      }

      public boolean messagesEmpty() {
        return wire.messages().isEmpty();
      }

      public String lastRole() {
        return wire.messages().getLast().role();
      }

      public String lastContent() {
        return wire.messages().getLast().content();
      }
    };
  }

  public String context() {
    return records.context(task);
  }

  public History lockHistory() {
    var history = records.history(task, true);
    return new History() {
      public long nextSeq() {
        return n(history, "next_seq");
      }

      public String bindingDigest() {
        return s(history, "binding_digest");
      }
    };
  }

  public void updateStep(long upper, String contextDigest, String bindingDigest) {
    records.updatePreparedStep(
        s(task, "tenant_id"), s(task, "id"), step, upper, contextDigest, bindingDigest);
  }

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public Scope requireScope() {
    var scope = records.scopes.require(task);
    return () -> s(scope.row(), "authority_digest");
  }

  public void insertScope(String context, Scope scope) {
    records.insertBinding(
        SessionPreparedScopeMapper.class,
        new SessionPreparedScopeEntity(
            s(task, "tenant_id"),
            step,
            s(task, "id"),
            scope.authorityDigest(),
            context,
            Payloads.hash(context),
            SessionContracts.bytes(context)));
  }

  public void body(long additional) {
    records.body(task, additional);
  }
}
