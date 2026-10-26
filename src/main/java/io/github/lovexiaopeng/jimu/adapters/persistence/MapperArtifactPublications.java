package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactPublications;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationCategory;
import io.github.lovexiaopeng.jimu.kernel.budget.api.ReservationState;
import io.github.lovexiaopeng.jimu.kernel.budget.api.UsageQuality;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 与原账本共用 DS/UoW/query；行包装仅保存引用，字段在原业务到达处读取。 */
final class MapperArtifactPublications implements ArtifactPublications {
  private final JdbcLedger ledger;

  MapperArtifactPublications(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public <T> T transaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public Task fenced(Lease lease, boolean admission) {
    var row = ledger.fenced(lease, admission);
    return new Task() {
      public boolean publicReport() {
        return ledger.publicReport(row);
      }

      public void event(String fact, String type) {
        ledger.event(row, fact, type, null);
      }
    };
  }

  public Budget bucket(Lease lease) {
    var row = ledger.bucket(lease.tenant());
    return new Budget() {
      public long storageHeld() {
        return ledger.storageHeld(row);
      }

      public long storageLimit() {
        return JdbcLedger.n(row, "storage_limit");
      }
    };
  }

  public List<Artifact> taskArtifacts(Lease lease) {
    return ledger
        .mappers
        .artifacts()
        .selectTaskArtifacts(ledger.queryContext(), lease.tenant(), lease.taskId())
        .stream()
        .<Artifact>map(Row::new)
        .toList();
  }

  public String lastSucceededInvocation(Lease lease) {
    return JdbcLedger.s(
        JdbcLedger.first(
            ledger
                .mappers
                .artifacts()
                .selectLastSucceededInvocation(
                    ledger.queryContext(), lease.tenant(), lease.taskId())),
        "id");
  }

  public String newId() {
    return JdbcLedger.id();
  }

  public void insert(
      Lease lease, String artifact, String producer, String upload, String hash, long size) {
    ledger
        .mappers
        .artifacts()
        .insertStaging(
            ledger.queryContext(),
            lease.tenant(),
            artifact,
            lease.taskId(),
            producer,
            upload,
            lease.fence(),
            hash,
            size);
  }

  public void holdStorage(Lease lease, long size) {
    ledger.mappers.artifacts().holdStorage(ledger.queryContext(), size, lease.tenant());
  }

  public void reserveStorage(Lease lease, String artifact, long size) {
    ledger.reserve(
        lease.tenant(),
        lease.taskId(),
        artifact,
        null,
        ReservationCategory.STORAGE,
        size,
        ReservationState.RESERVED,
        UsageQuality.KNOWN);
  }

  public void verify(Lease lease, String artifact, String key) {
    ledger
        .mappers
        .artifacts()
        .markVerified(
            ledger.queryContext(), key, lease.fence(), lease.tenant(), artifact, lease.taskId());
  }

  public Artifact artifact(Lease lease, String artifact) {
    return new Row(
        JdbcLedger.first(
            ledger
                .mappers
                .artifacts()
                .selectTaskArtifact(
                    ledger.queryContext(), lease.tenant(), artifact, lease.taskId())));
  }

  public void publish(Lease lease, String artifact) {
    ledger
        .mappers
        .artifacts()
        .markPublished(ledger.queryContext(), lease.fence(), lease.tenant(), artifact);
  }

  public void settleStorage(Lease lease, String artifact) {
    ledger.mappers.artifacts().settleStorage(ledger.queryContext(), lease.tenant(), artifact);
  }

  private record Row(Map<String, Object> row) implements Artifact {
    public String id() {
      return JdbcLedger.s(row, "id");
    }

    public String uploadId() {
      return JdbcLedger.s(row, "upload_id");
    }

    public String state() {
      return JdbcLedger.s(row, "state");
    }

    public String hash() {
      return JdbcLedger.s(row, "sha256");
    }

    public long size() {
      return JdbcLedger.n(row, "size");
    }

    public String snapshotKey() {
      return JdbcLedger.s(row, "snapshot_key");
    }
  }
}
