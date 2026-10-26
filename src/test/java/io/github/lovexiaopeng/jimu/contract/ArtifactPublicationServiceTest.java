package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ArtifactPublications;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ArtifactPublicationServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 分支、首错和事务内次序；实际 SQL/更新计数及恢复另由私有 MySQL 入口验证。 */
class ArtifactPublicationServiceTest {
  static final Lease LEASE =
      new Lease("tenant", "actor", "task", "attempt", 7, "worker", null, "goal", List.of(), 1);

  static class Harness {
    final List<String> trace = new ArrayList<>();
    boolean inside;
    boolean report;
    long held;
    long limit = 1048576;
    long bytes = 3;
    String hash = "hash";
    String state = "VERIFIED";
    String key = "key";
    String failAt;
    Throwable failure;
    int ids;
    List<ArtifactPublications.Artifact> existing = List.of();
    final ArtifactPublications.Task task = proxy(ArtifactPublications.Task.class);
    final ArtifactPublications.Budget budget = proxy(ArtifactPublications.Budget.class);
    final ArtifactPublications.Artifact row = proxy(ArtifactPublications.Artifact.class);
    final ArtifactPublications port = proxy(ArtifactPublications.class);
    final ArtifactPublicationServiceImpl service = new ArtifactPublicationServiceImpl(port);

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, a) -> {
                String name = m.getName();
                trace.add(name);
                if (name.equals("transaction")) {
                  assertSame(LEASE, a[0]);
                  assertTrue(!inside);
                  inside = true;
                  try {
                    return ((Supplier<?>) a[1]).get();
                  } finally {
                    inside = false;
                    trace.add("end");
                  }
                }
                assertTrue(inside, name);
                if (name.equals(failAt)) throw failure;
                if (type == ArtifactPublications.class && !name.equals("newId"))
                  assertSame(LEASE, a[0]);
                return switch (name) {
                  case "fenced" -> {
                    assertEquals(true, a[1]);
                    yield task;
                  }
                  case "publicReport" -> report;
                  case "bucket" -> budget;
                  case "storageHeld" -> held;
                  case "storageLimit" -> limit;
                  case "taskArtifacts" -> existing;
                  case "artifact" -> row;
                  case "lastSucceededInvocation" -> "producer";
                  case "newId" -> "id" + ++ids;
                  case "id" -> "artifact";
                  case "uploadId" -> "upload";
                  case "state" -> state;
                  case "hash" -> hash;
                  case "size" -> bytes;
                  case "snapshotKey" -> key;
                  case "event" -> {
                    trace.add(a[0] + "/" + a[1]);
                    yield null;
                  }
                  default -> null;
                };
              });
    }

    void code(String code, Runnable action) {
      assertEquals(code, assertThrows(TaskFailure.class, action::run).code());
      assertEquals("end", trace.getLast());
    }
  }

  @Test
  void newStagingOwnsTransactionAndStorageReservationEventOrder() {
    var h = new Harness();
    assertTrue(h.trace.isEmpty(), "construction is lazy");
    assertEquals(
        new Upload("id1", "id2", "STAGING", "hash", 3, null), h.service.stage(LEASE, "hash", 3));
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "publicReport",
            "bucket",
            "taskArtifacts",
            "lastSucceededInvocation",
            "storageHeld",
            "storageLimit",
            "newId",
            "newId",
            "insert",
            "holdStorage",
            "reserveStorage",
            "event",
            "staging:id1/artifact.staging",
            "end"),
        h.trace);
  }

  @Test
  void firstExistingArtifactBypassesProducerBudgetAndWrites() {
    var h = new Harness();
    h.limit = 0;
    h.existing = List.of(h.row, h.row);
    assertEquals(
        new Upload("artifact", "upload", "VERIFIED", "hash", 3, "key"),
        h.service.stage(LEASE, "hash", 3));
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "publicReport",
            "bucket",
            "taskArtifacts",
            "hash",
            "size",
            "id",
            "uploadId",
            "state",
            "snapshotKey",
            "end"),
        h.trace);
  }

  @Test
  void mismatchedDigestShortCircuitsSizeAndNullDigestRetainsFirstFault() {
    var h = new Harness();
    h.existing = List.of(h.row);
    h.code("ARTIFACT_MISMATCH", () -> h.service.stage(LEASE, "other", 3));
    assertTrue(!h.trace.contains("size"));
    h.trace.clear();
    assertThrows(NullPointerException.class, () -> h.service.stage(LEASE, null, 3));
    assertTrue(h.trace.contains("hash") && !h.trace.contains("size"));
  }

  @Test
  void sizeMismatchAndBothBudgetLimitsRetainOriginalShortCircuit() {
    var h = new Harness();
    h.existing = List.of(h.row);
    h.code("ARTIFACT_MISMATCH", () -> h.service.stage(LEASE, "hash", 4));
    h.existing = List.of();
    h.trace.clear();
    h.code("STORAGE_BUDGET_EXCEEDED", () -> h.service.stage(LEASE, "hash", 1048577));
    assertTrue(h.trace.contains("lastSucceededInvocation") && !h.trace.contains("storageHeld"));
    h.trace.clear();
    h.held = 1048574;
    h.code("STORAGE_BUDGET_EXCEEDED", () -> h.service.stage(LEASE, "hash", 3));
    assertTrue(!h.trace.contains("newId"));
  }

  @Test
  void reportGuardOnlyAppliesToStageAndPublish() {
    var h = new Harness();
    h.report = true;
    h.code("PRESENT_COMMIT_REQUIRED", () -> h.service.stage(LEASE, "hash", 3));
    assertEquals(List.of("transaction", "fenced", "publicReport", "end"), h.trace);
    h.trace.clear();
    h.code("PRESENT_COMMIT_REQUIRED", () -> h.service.publish(LEASE, "artifact"));
    assertEquals(List.of("transaction", "fenced", "publicReport", "end"), h.trace);
    h.trace.clear();
    h.service.verified(LEASE, "artifact", null);
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "verify",
            "event",
            "verified:artifact/artifact.verified",
            "end"),
        h.trace);
  }

  @Test
  void publishSettlesBeforeEventAndPublishedReplayNeverReadsKey() {
    var h = new Harness();
    h.service.publish(LEASE, "artifact");
    assertEquals(
        List.of(
            "transaction",
            "fenced",
            "publicReport",
            "artifact",
            "state",
            "state",
            "snapshotKey",
            "publish",
            "settleStorage",
            "event",
            "published:artifact/artifact.created",
            "end"),
        h.trace);
    h.trace.clear();
    h.state = "PUBLISHED";
    h.key = null;
    h.service.publish(LEASE, "artifact");
    assertEquals(
        List.of("transaction", "fenced", "publicReport", "artifact", "state", "end"), h.trace);
  }

  @Test
  void unverifiedAndNullKeyRejectBeforeWrites() {
    var h = new Harness();
    h.state = "STAGING";
    h.code("ARTIFACT_UNVERIFIED", () -> h.service.publish(LEASE, "artifact"));
    assertTrue(!h.trace.contains("snapshotKey"));
    h.state = "VERIFIED";
    h.key = null;
    h.trace.clear();
    h.code("ARTIFACT_UNVERIFIED", () -> h.service.publish(LEASE, "artifact"));
    assertTrue(h.trace.contains("snapshotKey") && !h.trace.contains("publish"));
  }

  @Test
  void eachStageFailureStopsLaterEffectsAndPreservesThrowableInstance() {
    for (String fail :
        List.of(
            "fenced",
            "publicReport",
            "bucket",
            "taskArtifacts",
            "lastSucceededInvocation",
            "storageHeld",
            "storageLimit",
            "newId",
            "insert",
            "holdStorage",
            "reserveStorage",
            "event")) {
      var h = new Harness();
      h.failAt = fail;
      h.failure = new AssertionError(fail);
      assertSame(
          h.failure, assertThrows(AssertionError.class, () -> h.service.stage(LEASE, "hash", 3)));
      assertEquals(fail, h.trace.get(h.trace.size() - 2));
    }
  }

  @Test
  void verifyAndPublishFailuresPropagateWithoutFallbackOrFurtherEffects() {
    for (String fail : List.of("fenced", "verify", "event")) {
      var h = new Harness();
      h.failAt = fail;
      h.failure = new IllegalStateException(fail);
      assertSame(
          h.failure,
          assertThrows(
              IllegalStateException.class, () -> h.service.verified(LEASE, "artifact", "key")));
      assertEquals(fail, h.trace.get(h.trace.size() - 2));
    }
    for (String fail :
        List.of(
            "fenced",
            "publicReport",
            "artifact",
            "state",
            "snapshotKey",
            "publish",
            "settleStorage",
            "event")) {
      var h = new Harness();
      h.failAt = fail;
      h.failure = new IllegalStateException(fail);
      assertSame(
          h.failure,
          assertThrows(IllegalStateException.class, () -> h.service.publish(LEASE, "artifact")));
      assertEquals(fail, h.trace.get(h.trace.size() - 2));
    }
  }
}
