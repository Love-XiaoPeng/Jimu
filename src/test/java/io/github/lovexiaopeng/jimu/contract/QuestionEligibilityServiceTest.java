package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionEligibility;
import io.github.lovexiaopeng.jimu.kernel.question.service.impl.QuestionEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionEligibilityServiceTest {
  final QuestionEligibilityServiceImpl service = new QuestionEligibilityServiceImpl();
  static final List<String> ORDER =
      List.of(
          "pin",
          "version1",
          "contract",
          "installation",
          "version2",
          "installed.version",
          "digest1",
          "installed.digest",
          "digest2",
          "contractOutputDigest",
          "epoch",
          "installed.epoch",
          "cleanupState",
          "bindingSnapshot",
          "hash",
          "bindingDigest",
          "version3",
          "expectedBinding");

  static final class Fixture {
    final Object pin = new Object(), contract = new Object();
    final List<String> calls = new ArrayList<>();
    final Map<String, Object> values = new HashMap<>();
    String fault;
    Throwable failure = new IllegalStateException("original");
    int versions, digests;

    Fixture() {
      for (String key : List.of("version1", "version2", "version3", "installed.version"))
        values.put(key, "0.2.0");
      for (String key :
          List.of(
              "digest1",
              "digest2",
              "installed.digest",
              "contractOutputDigest",
              "hash",
              "bindingDigest")) values.put(key, "digest");
      values.put("epoch", 7L);
      values.put("installed.epoch", 7L);
      values.put("cleanupState", "RETAINED");
      values.put("bindingSnapshot", " raw binding ");
      values.put("expectedBinding", " raw binding ");
    }

    Object read(String name) {
      calls.add(name);
      if (name.equals(fault)) {
        if (failure instanceof RuntimeException e) throw e;
        throw (Error) failure;
      }
      return values.get(name);
    }

    @SuppressWarnings("unchecked")
    QuestionEligibility<Object, Object> port() {
      return (QuestionEligibility<Object, Object>)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {QuestionEligibility.class},
              (p, m, a) -> {
                String name = m.getName();
                if (name.equals("version")) {
                  assertSame(pin, a[0]);
                  name = "version" + (++versions);
                } else if (name.equals("digest")) {
                  assertSame(pin, a[0]);
                  name = "digest" + (++digests);
                } else if (name.equals("epoch")) assertSame(pin, a[0]);
                if (name.equals("contract")) assertEquals(values.get("version1"), a[0]);
                if (name.equals("contractOutputDigest")) assertSame(contract, a[0]);
                if (name.equals("hash")) assertEquals(values.get("bindingSnapshot"), a[0]);
                if (name.equals("expectedBinding")) assertEquals(values.get("version3"), a[0]);
                Object value = read(name);
                return switch (name) {
                  case "pin" -> pin;
                  case "contract" -> contract;
                  case "installation" ->
                      Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {QuestionEligibility.Installation.class},
                          (x, method, args) -> read("installed." + method.getName()));
                  default -> value;
                };
              });
    }
  }

  void reject(Fixture f, String code, int status) {
    var failure = assertThrows(TaskFailure.class, () -> service.access(f.port()));
    assertEquals(code, failure.code());
    assertEquals(status, failure.status());
  }

  @Test
  void completeOrderAndIndependentCalls() {
    var f = new Fixture();
    service.access(f.port());
    assertEquals(ORDER, f.calls);
    assertEquals(3, f.versions);
    assertEquals(2, f.digests);
    f.calls.clear();
    f.versions = 0;
    f.digests = 0;
    service.access(f.port());
    assertEquals(ORDER, f.calls);
  }

  @Test
  void eachPinComparisonShortCircuits() {
    for (String key :
        List.of(
            "installed.version", "installed.digest", "contractOutputDigest", "installed.epoch")) {
      var f = new Fixture();
      f.values.put(key, key.equals("installed.epoch") ? 8L : "bad");
      reject(f, "PLUGIN_PIN_MISMATCH", 409);
      assertEquals(ORDER.subList(0, ORDER.indexOf(key) + 1), f.calls);
    }
  }

  @Test
  void nullReceiverStillEvaluatesItsParameter() {
    for (var pair :
        List.of(
            List.of("version2", "installed.version"),
            List.of("digest1", "installed.digest"),
            List.of("digest2", "contractOutputDigest"))) {
      var f = new Fixture();
      f.values.put(pair.getFirst(), null);
      assertThrows(NullPointerException.class, () -> service.access(f.port()));
      assertEquals(ORDER.subList(0, ORDER.indexOf(pair.getLast()) + 1), f.calls);
    }
  }

  @Test
  void nullableInstalledStringsAreOriginalMismatch() {
    for (String key : List.of("installed.version", "installed.digest", "contractOutputDigest")) {
      var f = new Fixture();
      f.values.put(key, null);
      reject(f, "PLUGIN_PIN_MISMATCH", 409);
      assertEquals(ORDER.subList(0, ORDER.indexOf(key) + 1), f.calls);
    }
  }

  @Test
  void purgedPrecedesBinding() {
    var f = new Fixture();
    f.values.put("cleanupState", "PURGED");
    reject(f, "QUESTION_CONTENT_EXPIRED", 410);
    assertEquals(ORDER.subList(0, 13), f.calls);
  }

  @Test
  void onlyExactPurgedRejects() {
    for (String state : Arrays.asList(null, "purged", "OTHER")) {
      var f = new Fixture();
      f.values.put("cleanupState", state);
      service.access(f.port());
      assertEquals(ORDER, f.calls);
    }
  }

  @Test
  void nullBindingStopsBeforeHashAndVersion() {
    var f = new Fixture();
    f.values.put("bindingSnapshot", null);
    reject(f, "SNAPSHOT_CORRUPT", 409);
    assertEquals(ORDER.subList(0, 14), f.calls);
  }

  @Test
  void hashMismatchStopsBeforeExpectedBinding() {
    var f = new Fixture();
    f.values.put("bindingDigest", "bad");
    reject(f, "SNAPSHOT_CORRUPT", 409);
    assertEquals(ORDER.subList(0, 16), f.calls);
  }

  @Test
  void nullHashReadsDigestBeforeNpe() {
    var f = new Fixture();
    f.values.put("hash", null);
    assertThrows(NullPointerException.class, () -> service.access(f.port()));
    assertEquals(ORDER.subList(0, 16), f.calls);
  }

  @Test
  void rawBindingIsComparedWithoutJsonNormalization() {
    var f = new Fixture();
    f.values.put("expectedBinding", "raw binding");
    reject(f, "SNAPSHOT_CORRUPT", 409);
    assertEquals(ORDER, f.calls);
  }

  @Test
  void bindingVersionIsReadAgainAtFinalPosition() {
    var f = new Fixture();
    f.values.put("version3", "0.1.0");
    service.access(f.port());
    assertEquals(ORDER, f.calls);
  }

  @Test
  void nullExpectedBindingRemainsNpe() {
    var f = new Fixture();
    f.values.put("expectedBinding", null);
    assertThrows(NullPointerException.class, () -> service.access(f.port()));
    assertEquals(ORDER, f.calls);
  }

  @Test
  void originalExceptionAtEveryCallbackPropagates() {
    for (String at : ORDER) {
      var f = new Fixture();
      f.fault = at;
      assertSame(
          f.failure, assertThrows(IllegalStateException.class, () -> service.access(f.port())));
      assertEquals(ORDER.subList(0, ORDER.indexOf(at) + 1), f.calls);
    }
  }

  @Test
  void contractFailurePrecedesMissingInstallation() {
    var f = new Fixture();
    f.fault = "contract";
    f.failure = new TaskFailure(409, "PLUGIN_PIN_MISMATCH");
    assertSame(f.failure, assertThrows(TaskFailure.class, () -> service.access(f.port())));
    assertEquals(ORDER.subList(0, 3), f.calls);
  }

  @Test
  void errorsAreNotTranslated() {
    var f = new Fixture();
    f.fault = "installation";
    f.failure = new AssertionError("original");
    assertSame(f.failure, assertThrows(AssertionError.class, () -> service.access(f.port())));
    assertEquals(ORDER.subList(0, 4), f.calls);
  }
}
