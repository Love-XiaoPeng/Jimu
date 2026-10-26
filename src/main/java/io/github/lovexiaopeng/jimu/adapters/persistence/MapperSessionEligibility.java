package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionEligibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEligibility;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;
import java.util.Map;

/** Lazy original values and one full-key shared lock per capability; no eligibility branches. */
final class MapperSessionEligibility implements SessionEligibility.Access {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperSessionEligibility(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public void history() {
    ledger.sessions.history(task, false);
  }

  public List<String> capabilities() {
    return ledger.caps(task);
  }

  public ToolDescriptor descriptor(String name) {
    return ledger.sessions.descriptor(task, name);
  }

  public PluginPin pin(String name) {
    return ledger.tools(task).pins().get(name);
  }

  public SessionEligibility.Installation installation(String name) {
    var rows =
        ledger
            .mappers
            .session()
            .getMapper(SessionEligibilityMapper.class)
            .lockInstallation(ledger.queryContext(), s(task, "tenant_id"), name);
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    var row = rows.getFirst();
    return new SessionEligibility.Installation() {
      public String version() {
        return s(row, "plugin_version");
      }

      public String digest() {
        return s(row, "plugin_digest");
      }

      public long epoch() {
        return n(row, "epoch");
      }
    };
  }

  SessionEligibility.Admission admission() {
    return new SessionEligibility.Admission() {
      public boolean readEnabled() {
        return ledger.sessionReadEnabled;
      }

      public boolean scope() {
        return JdbcSessionScopes.task(task);
      }

      public boolean scopeEnabled() {
        return ledger.priorSessionSearchEnabled;
      }

      public boolean search() {
        return JdbcSessionRecords.searchTask(task);
      }

      public boolean searchEnabled() {
        return ledger.sessionSearchEnabled;
      }

      public boolean trace() {
        return JdbcSessionRecords.traceTask(task);
      }

      public boolean traceEnabled() {
        return ledger.sessionTraceEnabled;
      }

      public void history() {
        ledger.sessions.history(task, true);
      }

      public ModelSnapshot snapshot() {
        return ledger.snapshot(task);
      }

      public void requireTrace(ModelSnapshot snapshot) {
        ledger.models.requireSessionTrace(snapshot);
      }

      public void requireSearch(ModelSnapshot snapshot) {
        ledger.models.requireSessionSearch(snapshot);
      }

      public void requireRead(ModelSnapshot snapshot) {
        ledger.models.requireSessionRead(snapshot);
      }
    };
  }
}
