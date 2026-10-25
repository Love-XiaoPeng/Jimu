package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAdmission;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.Map;

/** Construction performs no reads, decoding, query or session lookup. */
final class MapperDirectoryAdmission implements DirectoryAdmission {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperDirectoryAdmission(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public boolean enabled() {
    return ledger.directoryEnabled;
  }

  public Projection projection() {
    var row = ledger.directoryProjection(task);
    return () -> s(row, "content_state");
  }

  public ModelSnapshot snapshot() {
    return ledger.snapshot(task);
  }

  public void requireDirectory(ModelSnapshot snapshot) {
    ledger.models.requireDirectory(snapshot);
  }
}
