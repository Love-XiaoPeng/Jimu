package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactDownloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;

/** 与原账本共用SqlSession、显式query与事务；不额外锁artifact或改变清理竞争语义。 */
final class MapperArtifactDownloads implements ArtifactDownloads {
  private final JdbcLedger ledger;

  MapperArtifactDownloads(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public void requireIdentity(Tasks.Identity identity) {
    ledger.identity(identity);
  }

  @Override
  public Candidate findOwnedRetained(Tasks.Identity identity, String artifactId) {
    var row =
        JdbcLedger.first(
            ledger
                .mappers
                .artifacts()
                .selectOwnedRetained(
                    ledger.queryContext(), identity.tenant(), artifactId, identity.actor()));
    return new Candidate(
        new Tasks.Artifact(
            JdbcLedger.s(row, "id"),
            JdbcLedger.s(row, "media_type"),
            JdbcLedger.n(row, "size"),
            JdbcLedger.s(row, "sha256"),
            JdbcLedger.time(row, "expires_at")),
        JdbcLedger.s(row, "task_id"),
        JdbcLedger.s(row, "snapshot_key"));
  }

  @Override
  public void requireTaskAccess(Tasks.Identity identity, String taskId) {
    ledger.access(ledger.visible(identity, taskId));
  }
}
