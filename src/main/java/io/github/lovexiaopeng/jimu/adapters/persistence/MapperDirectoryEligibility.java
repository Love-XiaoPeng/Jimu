package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.directory.mapper.DirectoryEligibilityMapper;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryEligibility;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;
import java.util.Map;

/** No values or sessions are obtained at construction; original callbacks remain lazy. */
final class MapperDirectoryEligibility implements DirectoryEligibility {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperDirectoryEligibility(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public List<String> capabilities() {
    return ledger.caps(task);
  }

  public PluginPin pin(String name) {
    return ledger.tools(task).pins().get(name);
  }

  public Installation installation(String name) {
    var rows =
        ledger
            .mappers
            .session()
            .getMapper(DirectoryEligibilityMapper.class)
            .lockInstallation(ledger.queryContext(), s(task, "tenant_id"), name);
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    var row = rows.getFirst();
    return new Installation() {
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

  public ToolDescriptor directoryDescriptor() {
    return ledger.directories.descriptor();
  }

  public ToolDescriptor fileDescriptor(String name) {
    return ledger.directoryFileContract(name).descriptor();
  }

  public void projection() {
    ledger.directoryProjection(task);
  }
}
