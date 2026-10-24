package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactDownloads;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactAccessServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 保留到期边界、当前授权优先级和事务外存储IO，不以空转发验证代替规则。 */
class ArtifactDownloadServiceTest {
  static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
  static final Tasks.Identity OWNER = new Tasks.Identity("tenant", "actor");
  final List<String> calls = new ArrayList<>();
  boolean active;
  boolean revoke;
  Instant expiry = NOW;
  final LedgerUnitOfWork work =
      new LedgerUnitOfWork() {
        public <T> T required(Supplier<T> action) {
          active = true;
          try {
            return action.get();
          } finally {
            active = false;
          }
        }
      };
  final ArtifactDownloads records =
      new ArtifactDownloads() {
        public void requireIdentity(Tasks.Identity identity) {
          calls.add("identity");
        }

        public Candidate findOwnedRetained(Tasks.Identity identity, String id) {
          calls.add("owned-retained");
          return new Candidate(
              new Tasks.Artifact(id, "text/plain", 3, "digest", expiry), "task", "snapshot");
        }

        public void requireTaskAccess(Tasks.Identity identity, String task) {
          calls.add("current-access");
          if (revoke) throw new TaskFailure(403, "CAPABILITY_REVOKED");
        }
      };

  ArtifactAccessServiceImpl service() {
    return new ArtifactAccessServiceImpl(records, work, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void exactExpiryInstantRetainsOriginalStrictBeforeBoundary() {
    assertEquals("snapshot", service().authorize(OWNER, "id").snapshotKey());
    assertEquals(List.of("identity", "owned-retained", "current-access"), calls);
    assertFalse(active);
  }

  @Test
  void expiredArtifactStillRechecksCurrentAuthorityFirst() {
    expiry = NOW.minusNanos(1);
    revoke = true;
    assertEquals(
        "CAPABILITY_REVOKED",
        assertThrows(TaskFailure.class, () -> service().authorize(OWNER, "id")).code());
    revoke = false;
    assertEquals(
        "ARTIFACT_EXPIRED",
        assertThrows(TaskFailure.class, () -> service().authorize(OWNER, "id")).code());
    assertFalse(active);
  }

  @Test
  void immutableMetadataGoesToStorageAfterTransactionAndFailurePropagates() {
    var store =
        new ArtifactStore() {
          public String write(String tenant, String id, byte[] bytes, String hash) {
            throw new UnsupportedOperationException();
          }

          public void check() {}

          public void removeUpload(String tenant, String id) {
            throw new UnsupportedOperationException();
          }

          public byte[] read(String tenant, String key, String hash, long size) {
            assertFalse(active);
            assertEquals("tenant", tenant);
            assertEquals("snapshot", key);
            assertEquals("digest", hash);
            assertEquals(3, size);
            throw new IllegalStateException("corrupt snapshot");
          }
        };
    var downloads = new ArtifactServiceImpl(service(), store);
    assertEquals(
        "corrupt snapshot",
        assertThrows(IllegalStateException.class, () -> downloads.download(OWNER, "id"))
            .getMessage());
  }
}
