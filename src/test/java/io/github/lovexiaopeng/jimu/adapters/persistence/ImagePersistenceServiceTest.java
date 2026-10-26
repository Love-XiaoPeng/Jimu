package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.media.port.ImageRecords;
import io.github.lovexiaopeng.jimu.kernel.media.service.impl.ImagePersistenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Synthetic first-error/order checks; real transaction and SQL evidence is in the bounded IT. */
class ImagePersistenceServiceTest {
  static final Lease LEASE =
      new Lease("t", "a", "task", "attempt", 1, "w", null, "g", List.of(), 1);
  static final TaskImages.Entry ENTRY =
      new TaskImages.Entry("input", "inputs/images/a.png", Payloads.hash(new byte[] {1}), 1);
  static final ImagePort.Result RESULT =
      new ImagePort.Result(
          ENTRY.filePath(),
          ENTRY.inputId(),
          "image:inv",
          ENTRY.sha256(),
          1,
          new ImagePort.Image("image", "image/png", "sha", 1, 3, 2, 3, 2, "jimu-image-v1"));

  static class Harness {
    final List<String> trace = new ArrayList<>();
    final Map<String, Object> row = new HashMap<>();
    final ImageRecords.Task task = new MapperImageRecords(null).bind(row);
    final MapperImageRecords codec = new MapperImageRecords(null);
    final ImageRecords records = proxy(ImageRecords.class);
    final ImagePersistenceServiceImpl service = new ImagePersistenceServiceImpl(records);
    boolean inside;
    long valid = 1, limit = 2097152;
    Object revoked, raw = "AQ==", path = ENTRY.filePath();
    int inputRows = 1, invocationRows = 1, commitCount = 1, inputReads;
    boolean producer;
    String failAt;
    final RuntimeException failure = new IllegalStateException("sentinel");

    Harness() {
      row.put("tenant_id", "t");
      row.put("id", "task");
      row.put("record_version", 3);
      String snapshot = Payloads.json(TaskImages.snapshot(List.of(ENTRY)));
      row.put("media_snapshot", snapshot);
      row.put("media_digest", Payloads.hash(snapshot));
      row.put(
          "request_snapshot",
          Payloads.json(
              new Tasks.Request(
                  "g",
                  List.of(),
                  List.of(),
                  null,
                  List.of(new TaskImages.Descriptor(ENTRY.filePath(), ENTRY.sha256(), 1)))));
    }

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type) {
      return (T)
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (p, m, a) -> {
                String name = m.getName();
                trace.add(name);
                if (name.equals(failAt)) throw failure;
                return switch (name) {
                  case "leaseTransaction" -> {
                    assertSame(LEASE, a[0]);
                    assertFalse(inside);
                    inside = true;
                    try {
                      yield ((Supplier<?>) a[1]).get();
                    } finally {
                      inside = false;
                      trace.add("end");
                    }
                  }
                  case "fenced" -> {
                    assertTrue(inside);
                    yield task;
                  }
                  case "requireImages", "event", "reserveStorage", "toolSucceeded" -> null;
                  case "budget" -> proxy(ImageRecords.Budget.class);
                  case "storageLimit" -> limit;
                  case "storageHeld", "mediaCheckpointHeld" -> 0L;
                  case "id" -> "image";
                  case "hash" ->
                      a[0] instanceof String s ? Payloads.hash(s) : Payloads.hash((byte[]) a[0]);
                  case "json" -> Payloads.json(a[0]);
                  case "readSnapshot" -> codec.readSnapshot((String) a[0]);
                  case "readRequest" -> codec.readRequest((String) a[0]);
                  case "decode" -> codec.decode((String) a[0]);
                  case "selectImageInput" -> {
                    inputReads++;
                    yield inputRows == 0
                        ? List.of()
                        : inputRows == 2
                            ? List.of(
                                proxy(ImageRecords.Input.class), proxy(ImageRecords.Input.class))
                            : List.of(proxy(ImageRecords.Input.class));
                  }
                  case "valid" -> valid;
                  case "revokedAt" -> revoked;
                  case "rawBase64" -> raw;
                  case "base64" -> (String) raw;
                  case "logicalPath" -> path;
                  case "path" -> (String) path;
                  case "rawSha256" -> ENTRY.sha256();
                  case "rawSize" -> 1L;
                  case "selectInputIds" -> List.of(proxy(ImageRecords.InputId.class));
                  case "inputId" -> ENTRY.inputId();
                  case "selectRunningImageInvocation" ->
                      invocationRows == 1
                          ? List.of(proxy(ImageRecords.Invocation.class))
                          : List.of();
                  case "inputSnapshot" -> "{}";
                  case "filePath" -> ENTRY.filePath();
                  case "selectProducerImages" ->
                      producer ? List.of(proxy(ImageRecords.Image.class)) : List.of();
                  case "commitImageObject" -> commitCount;
                  case "holdImageStorage",
                          "settleImageReservation",
                          "releaseImageStorage",
                          "cancelImageDecode",
                          "reserveImageObject",
                          "insertInput",
                          "saveMediaSnapshot" ->
                      0; // original ignored counts
                  default -> throw new AssertionError(name);
                };
              });
    }

    void reject(String code, Runnable run) {
      assertEquals(code, assertThrows(TaskFailure.class, run::run).code());
    }
  }

  @Test
  void constructionAndCompatibilityBindingReadNothing() {
    var map =
        new HashMap<String, Object>() {
          public Object get(Object key) {
            throw new AssertionError(key);
          }
        };
    var adapter = new MapperImageRecords(null);
    var task = adapter.bind(map);
    new ImagePersistenceServiceImpl(null);
    assertThrows(AssertionError.class, task::mediaSnapshot);
  }

  @Test
  void snapshotStringCastPrecedesVersionAndNullChecks() {
    var h = new Harness();
    h.row.put("media_snapshot", 1);
    h.row.put("record_version", null);
    assertThrows(ClassCastException.class, () -> h.service.snapshot(h.task));
    assertTrue(h.trace.isEmpty());
  }

  @Test
  void versionNumberFailurePrecedesNullSnapshot() {
    var h = new Harness();
    h.row.put("media_snapshot", null);
    h.row.put("record_version", "3");
    assertThrows(ClassCastException.class, () -> h.service.snapshot(h.task));
    assertTrue(h.trace.isEmpty());
  }

  @Test
  void wrongVersionShortCircuitsHashAndCodec() {
    var h = new Harness();
    h.row.put("record_version", 2);
    h.reject("SNAPSHOT_CORRUPT", () -> h.service.snapshot(h.task));
    assertTrue(h.trace.isEmpty());
  }

  @Test
  void taskParameterCastFailsBeforeMapperOrSessionAcquisition() {
    var h = new Harness();
    h.row.put("tenant_id", 1);
    assertThrows(ClassCastException.class, () -> h.service.snapshot(h.task));
    assertFalse(h.trace.contains("selectImageInput"));
  }

  @Test
  void availabilityExactOneAndValidShortCircuit() {
    for (int count : new int[] {0, 2}) {
      var h = new Harness();
      h.inputRows = count;
      h.reject("IMAGE_UNAVAILABLE", () -> h.service.snapshot(h.task));
      assertFalse(h.trace.contains("valid"));
    }
    var h = new Harness();
    h.valid = 0;
    h.revoked = new Object();
    h.reject("IMAGE_UNAVAILABLE", () -> h.service.snapshot(h.task));
    assertFalse(h.trace.contains("revokedAt"));
  }

  @Test
  void availabilityTestsRawNullWithoutPrematureStringCast() {
    var h = new Harness();
    h.raw = new Object();
    h.service.snapshot(h.task);
    assertFalse(h.trace.contains("base64"));
    assertThrows(ClassCastException.class, () -> h.service.raw(h.task, ENTRY.filePath()));
  }

  @Test
  void rawPreservesRepeatedInputReadsAndRawStringPathCast() {
    var h = new Harness();
    h.path = 1;
    assertThrows(ClassCastException.class, () -> h.service.raw(h.task, ENTRY.filePath()));
    assertEquals(2, h.inputReads);
  }

  @Test
  void reservePreservesThreeInputReadsAndUnknownNeverReexecutes() {
    var h = new Harness();
    h.producer = true;
    h.reject("EFFECT_OUTCOME_UNKNOWN", () -> h.service.reserve(h.task, "inv", "image", "input"));
    assertEquals(3, h.inputReads);
    assertFalse(h.trace.contains("reserveImageObject"));
  }

  @Test
  void reservationBudgetIsBeforeUuidOrIntegrity() {
    var h = new Harness();
    h.limit = 0;
    h.row.put("media_snapshot", 1);
    h.reject("STORAGE_BUDGET_EXCEEDED", () -> h.service.reserveImage(LEASE, "inv", "input"));
    assertFalse(h.trace.contains("id"));
    assertEquals(
        List.of(
            "leaseTransaction",
            "fenced",
            "budget",
            "storageLimit",
            "storageHeld",
            "mediaCheckpointHeld",
            "end"),
        h.trace);
  }

  @Test
  void zeroCommitCountStopsSettlementAndNestedSuccess() {
    var h = new Harness();
    h.commitCount = 0;
    h.reject("SNAPSHOT_CORRUPT", () -> h.service.imageSucceeded(LEASE, "inv", "image-sha", RESULT));
    assertFalse(h.trace.contains("settleImageReservation"));
    assertFalse(h.trace.contains("toolSucceeded"));
  }

  @Test
  void ignoredSettlementCountsKeepOriginalNestedSuccessOrderAndFailure() {
    var h = new Harness();
    h.failAt = "toolSucceeded";
    assertSame(
        h.failure,
        assertThrows(
            IllegalStateException.class,
            () -> h.service.imageSucceeded(LEASE, "inv", "image-sha", RESULT)));
    assertEquals(
        List.of(
            "commitImageObject",
            "settleImageReservation",
            "releaseImageStorage",
            "json",
            "toolSucceeded",
            "end"),
        h.trace.subList(h.trace.indexOf("commitImageObject"), h.trace.size()));
  }

  @Test
  void timeoutUsesNonAdmissionFenceAndOriginalEventAfterIgnoredUpdate() {
    var trace = new ArrayList<String>();
    var records =
        (ImageRecords)
            Proxy.newProxyInstance(
                ImageRecords.class.getClassLoader(),
                new Class<?>[] {ImageRecords.class},
                (p, m, a) -> {
                  trace.add(m.getName());
                  return switch (m.getName()) {
                    case "leaseTransaction" -> ((Supplier<?>) a[1]).get();
                    case "fenced" -> {
                      assertEquals(false, a[1]);
                      yield null;
                    }
                    case "cancelImageDecode" -> {
                      assertEquals(List.of("t", "task"), List.of(a));
                      yield 0;
                    }
                    case "event" -> {
                      assertEquals("IMAGE_DECODE_TIMEOUT", a[3]);
                      yield null;
                    }
                    default -> throw new AssertionError(m.getName());
                  };
                });
    new ImagePersistenceServiceImpl(records).imageDecodeTimedOut(LEASE);
    assertEquals(List.of("leaseTransaction", "fenced", "cancelImageDecode", "event"), trace);
  }
}
