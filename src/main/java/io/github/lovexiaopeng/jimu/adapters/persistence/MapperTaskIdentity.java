package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskIdentityRecords;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 复用原参数化 FOR SHARE；构造不取会话、不取身份字段、不触发核权。 */
final class MapperTaskIdentity implements TaskIdentityRecords {
  private final JdbcLedger ledger;

  MapperTaskIdentity(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public Rows<Actor> lockActor(Identity identity) {
    var result =
        ledger
            .mappers
            .session()
            .getMapper(TaskAdmissionMapper.class)
            .lockActor(ledger.queryContext(), identity.tenant(), identity.actor());
    return new RowsView<>(result, ActorView::new);
  }

  public Rows<Installation> lockInstallation(Identity identity, String capability) {
    var result =
        ledger
            .mappers
            .session()
            .getMapper(TaskAdmissionMapper.class)
            .lockInstallation(ledger.queryContext(), identity.tenant(), capability);
    return new RowsView<>(result, InstallationView::new);
  }

  private record RowsView<R>(List<Map<String, Object>> rows, Function<Map<String, Object>, R> view)
      implements Rows<R> {
    public boolean isEmpty() {
      return rows.isEmpty();
    }

    public R first() {
      return view.apply(rows.getFirst());
    }
  }

  private record ActorView(Map<String, Object> row) implements Actor {
    public Object enabled() {
      return row.get("enabled");
    }
  }

  private record InstallationView(Map<String, Object> row) implements Installation {
    public Object enabled() {
      return row.get("enabled");
    }

    public Number epoch() {
      return (Number) row.get("epoch");
    }
  }
}
