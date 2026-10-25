package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ImageAvailability;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ImageAvailabilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageAvailabilityServiceTest {
  private final ImageAvailabilityServiceImpl service = new ImageAvailabilityServiceImpl();
  private final Checks checks = new Checks();

  @Test
  void missingStoreSkipsBothChecks() {
    checks.present = false;
    assertFalse(service.available(checks));
    assertEquals(List.of("present"), checks.calls);
  }

  @Test
  void originalStoreThenCodecOrderRepeatsOnEveryCall() {
    assertTrue(service.available(checks));
    assertTrue(service.available(checks));
    assertEquals(List.of("present", "store", "codec", "present", "store", "codec"), checks.calls);
  }

  @Test
  void presenceFileFailureIsOutsideTry() {
    checks.point = "present";
    checks.failure = new FileFailure("PRESENCE");
    assertSame(checks.failure, assertThrows(FileFailure.class, () -> service.available(checks)));
    assertEquals(List.of("present"), checks.calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"store", "codec"})
  void onlyOriginalCheckFileFailureReturnsFalse(String point) {
    checks.point = point;
    checks.failure = new FileFailure("ORIGINAL", new IllegalStateException("cause"));
    assertFalse(service.available(checks));
    assertEquals(point, checks.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"present", "store", "codec"})
  void taskFailurePropagatesWithIdentityAndCause(String point) {
    checks.point = point;
    checks.failure = new TaskFailure(503, "ORIGINAL");
    assertSame(checks.failure, assertThrows(TaskFailure.class, () -> service.available(checks)));
    assertEquals(point, checks.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"present", "store", "codec"})
  void illegalStateIsNotConvertedToFalse(String point) {
    checks.point = point;
    checks.failure = new IllegalStateException("original", new java.io.IOException("store"));
    assertSame(
        checks.failure, assertThrows(IllegalStateException.class, () -> service.available(checks)));
    assertEquals(point, checks.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"present", "store", "codec"})
  void nullDereferencePropagates(String point) {
    checks.point = point;
    checks.failure = new NullPointerException(point);
    assertSame(
        checks.failure, assertThrows(NullPointerException.class, () -> service.available(checks)));
    assertEquals(point, checks.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"present", "store", "codec"})
  void errorIsNotCaught(String point) {
    checks.point = point;
    checks.error = new AssertionError(point);
    assertSame(checks.error, assertThrows(AssertionError.class, () -> service.available(checks)));
    assertEquals(point, checks.calls.getLast());
  }

  private static final class Checks implements ImageAvailability {
    final List<String> calls = new ArrayList<>();
    boolean present = true;
    String point;
    RuntimeException failure;
    Error error;

    void read(String name) {
      calls.add(name);
      if (name.equals(point)) {
        if (error != null) throw error;
        throw failure;
      }
    }

    public boolean storePresent() {
      read("present");
      return present;
    }

    public void checkStore() {
      read("store");
    }

    public void checkCodec() {
      read("codec");
    }
  }
}
