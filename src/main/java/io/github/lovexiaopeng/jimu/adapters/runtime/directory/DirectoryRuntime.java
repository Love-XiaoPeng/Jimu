package io.github.lovexiaopeng.jimu.adapters.runtime.directory;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WorkingDirectoryLedger;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.WorkingDirectoryPort;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.WorkingDirectoryPlugin;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** Trusted assembly of a fixed slot, secure workspace proof, and a short atomic DB commit. */
public final class DirectoryRuntime {
  private final SecureWorkspaceStore workspace;
  private final DirectoryContracts contracts = new DirectoryContracts();

  public DirectoryRuntime(SecureWorkspaceStore workspace) throws IOException {
    this.workspace = workspace.logicalDirectory();
  }

  public SecureWorkspaceStore workspace() {
    return workspace;
  }

  public void prepare(ExecutionLedger ledger, ExecutionLedger.Lease lease) {
    var directory = (WorkingDirectoryLedger) ledger;
    var snapshot = directory.directorySnapshot(lease);
    if (!snapshot.ready()) {
      workspace.initialize(lease.tenant(), lease.taskId(), true, () -> ledger.checkpoint(lease));
      try {
        directory.directoryReady(lease);
      } catch (DataAccessException | TransactionException unconfirmed) {
        throw new DirectoryCommitUnconfirmed();
      }
    }
    workspace.directoryEvidence(
        lease.tenant(), lease.taskId(), snapshot.cwd(), () -> ledger.checkpoint(lease));
  }

  public String invoke(
      ExecutionLedger ledger,
      ExecutionLedger.Lease lease,
      String stepId,
      ExecutionLedger.Call call,
      String raw,
      BoundaryProbe probe) {
    if (!call.input().equals(raw)) throw new TaskFailure(409, "INVOCATION_MISMATCH");
    var input = contracts.input(raw);
    var pin = ledger.toolSnapshot(lease).pins().get(call.tool());
    var directory = (WorkingDirectoryLedger) ledger;
    WorkingDirectoryLedger.Frozen frozen;
    try {
      frozen = directory.freezeDirectory(lease, stepId, call, pin.version(), pin.digest());
    } catch (DataAccessException | TransactionException unconfirmed) {
      throw new DirectoryCommitUnconfirmed();
    }
    probe.hit("DIRECTORY_FROZEN", lease.taskId());
    if (frozen.output() != null) return Payloads.json(contracts.output(frozen.output()));
    var proof =
        workspace.directoryEvidence(
            lease.tenant(), lease.taskId(), frozen.target(), () -> ledger.checkpoint(lease));
    var evidence =
        new WorkingDirectoryLedger.Evidence(
            Payloads.hash(lease.tenant() + ":" + lease.taskId()), frozen.target(), proof.digest());
    WorkingDirectoryPort port =
        new WorkingDirectoryPort() {
          private Result commit() {
            probe.hit("DIRECTORY_COMMIT_BEFORE", lease.taskId());
            try {
              var result = directory.commitDirectory(lease, frozen.invocationId(), evidence);
              probe.hit("DIRECTORY_COMMIT_AFTER", lease.taskId());
              return result;
            } catch (DataAccessException | TransactionException unconfirmed) {
              throw new DirectoryCommitUnconfirmed();
            }
          }

          @Override
          public Result current() {
            if (!frozen.mode().equals("READ")) throw new TaskFailure(409, "INVOCATION_MISMATCH");
            return commit();
          }

          @Override
          public Result change(String path) {
            if (!frozen.mode().equals("SET") || !path.equals(input.cd()))
              throw new TaskFailure(409, "INVOCATION_MISMATCH");
            return commit();
          }
        };
    var result =
        new WorkingDirectoryPlugin(port, contracts.descriptor())
            .execute(
                input,
                new ExecutionContext(
                    frozen.invocationId(), lease.deadline(), () -> ledger.checkpoint(lease)));
    return Payloads.json(result.output());
  }
}
