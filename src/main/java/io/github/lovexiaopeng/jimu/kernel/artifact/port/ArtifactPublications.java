package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.function.Supplier;

/** 产物发布事务中的惰性事实与单项写入；沿用账本的完整租约资格和事件边界。 */
public interface ArtifactPublications {
  interface Task {
    boolean publicReport();

    void event(String fact, String type);
  }

  interface Budget {
    long storageHeld();

    long storageLimit();
  }

  interface Artifact {
    String id();

    String uploadId();

    String state();

    String hash();

    long size();

    String snapshotKey();
  }

  <T> T transaction(Lease lease, Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  Budget bucket(Lease lease);

  List<Artifact> taskArtifacts(Lease lease);

  String lastSucceededInvocation(Lease lease);

  String newId();

  void insert(Lease lease, String artifact, String producer, String upload, String hash, long size);

  void holdStorage(Lease lease, long size);

  void reserveStorage(Lease lease, String artifact, long size);

  void verify(Lease lease, String artifact, String key);

  Artifact artifact(Lease lease, String artifact);

  void publish(Lease lease, String artifact);

  void settleStorage(Lease lease, String artifact);
}
