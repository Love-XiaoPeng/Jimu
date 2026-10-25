package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAdmission;
import io.github.lovexiaopeng.jimu.kernel.directory.service.impl.DirectoryAdmissionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectoryAdmissionServiceTest {
  private final DirectoryAdmissionServiceImpl service = new DirectoryAdmissionServiceImpl();
  private final Fixture port = new Fixture();

  @Test
  void disabledFlagAvoidsAllOtherReads() {
    port.enabled = false;
    var failure = assertThrows(TaskFailure.class, () -> service.check(port));
    assertEquals(403, failure.status());
    assertEquals("DIRECTORY_FEATURE_DISABLED", failure.code());
    assertEquals(List.of("enabled"), port.calls);
  }

  @Test
  void successKeepsOneFullProjectionAndHistoricalSnapshotBeforeRequirement() {
    service.check(port);
    assertEquals(
        List.of("enabled", "projection", "content", "historical", "available", "require"),
        port.calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PURGING", "PURGED", "", "present"})
  void nonPresentStopsBeforeHistoricalModel(String state) {
    port.content = state;
    expired();
  }

  @Test
  void nullContentUsesOriginal410() {
    port.content = null;
    expired();
  }

  private void expired() {
    var failure = assertThrows(TaskFailure.class, () -> service.check(port));
    assertEquals(410, failure.status());
    assertEquals("DIRECTORY_CONTENT_EXPIRED", failure.code());
    assertEquals(List.of("enabled", "projection", "content"), port.calls);
  }

  @Test
  void nonStringContentRetainsCastFailure() {
    port.content = 1;
    assertThrows(ClassCastException.class, () -> service.check(port));
    assertEquals(List.of("enabled", "projection", "content"), port.calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"enabled", "projection", "content", "historical", "available", "require"})
  void runtimeErrorsPropagateUnchangedAtTheirOriginalPosition(String point) {
    port.failAt = point;
    port.failure = new IllegalStateException(point);
    assertSame(port.failure, assertThrows(IllegalStateException.class, () -> service.check(port)));
    assertEquals(point, port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"projection", "historical", "available", "require"})
  void taskFailuresAreNotTranslatedOrSwallowed(String point) {
    port.failAt = point;
    port.failure = new TaskFailure(404, "ORIGINAL_" + point);
    assertSame(port.failure, assertThrows(TaskFailure.class, () -> service.check(port)));
    assertEquals(point, port.calls.getLast());
  }

  @Test
  void repeatedInvocationReadsFlagAndProjectionAgain() {
    service.check(port);
    port.enabled = false;
    assertThrows(TaskFailure.class, () -> service.check(port));
    assertEquals(2, port.calls.stream().filter("enabled"::equals).count());
    assertEquals(1, port.calls.stream().filter("projection"::equals).count());
  }

  private static final class Fixture implements DirectoryAdmission {
    final List<String> calls = new ArrayList<>();
    boolean enabled = true;
    Object content = "PRESENT";
    String failAt;
    RuntimeException failure;

    private void read(String name) {
      calls.add(name);
      if (name.equals(failAt)) throw failure;
    }

    public boolean enabled() {
      read("enabled");
      return enabled;
    }

    public Projection projection() {
      read("projection");
      return () -> {
        read("content");
        return (String) content;
      };
    }

    public ModelSnapshot snapshot() {
      read("historical");
      read("available");
      return null;
    }

    public void requireDirectory(ModelSnapshot snapshot) {
      assertSame(null, snapshot);
      read("require");
    }
  }
}
