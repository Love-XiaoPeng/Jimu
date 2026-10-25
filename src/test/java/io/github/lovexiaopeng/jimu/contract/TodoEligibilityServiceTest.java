package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoEligibility;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TodoEligibilityServiceTest {
  final TodoEligibilityServiceImpl service = new TodoEligibilityServiceImpl();
  final Fixture port = new Fixture();

  static ToolDescriptor descriptor(String version, String digest) {
    return new ToolDescriptor("todo_write", version, "todo_write", "{}", "{}", digest, "entry");
  }

  @Test
  void orderedSingleCapsPinLockTwoDescriptorsAndProjection() {
    service.access(port);
    assertEquals(
        List.of(
            "caps",
            "pin",
            "lock",
            "descriptor1",
            "descriptor2",
            "version",
            "digest",
            "epoch",
            "projection"),
        port.calls);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "todo_write,todo_write", "todo_write,read", "read,todo_write", "read"})
  void strictListRejectsDuplicatesAndMixed(String value) {
    port.caps = Arrays.asList(value.split(","));
    TaskFailure failure = assertThrows(TaskFailure.class, () -> service.access(port));
    assertEquals(409, failure.status());
    assertEquals("SNAPSHOT_CORRUPT", failure.code());
    assertEquals(List.of("caps"), port.calls);
  }

  @Test
  void nullCapsIsNotNormalized() {
    port.caps = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(List.of("caps"), port.calls);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "caps",
        "pin",
        "lock",
        "descriptor1",
        "descriptor2",
        "version",
        "digest",
        "epoch",
        "projection"
      })
  void exceptionsPropagateAtOriginalPosition(String point) {
    port.fail = point;
    var original = port.failure;
    assertSame(original, assertThrows(IllegalStateException.class, () -> service.access(port)));
    assertEquals(point, port.calls.getLast());
  }

  @Test
  void nullPinLocksBeforeNpeAndDoesNotConstructDescriptor() {
    port.pin = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(List.of("caps", "pin", "lock"), port.calls);
  }

  @Test
  void missingInstallationPrecedesNullPin() {
    port.pin = null;
    port.missing = true;
    assertEquals("NOT_FOUND", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals(List.of("caps", "pin", "lock"), port.calls);
  }

  @Test
  void nullPinVersionStillEvaluatesFirstDescriptorArgument() {
    port.pin = new PluginPin(null, "digest", 1);
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals("descriptor1", port.calls.getLast());
  }

  @Test
  void descriptorFailurePrecedesNullVersionEquals() {
    port.pin = new PluginPin(null, "digest", 1);
    port.fail = "descriptor1";
    assertSame(port.failure, assertThrows(IllegalStateException.class, () -> service.access(port)));
  }

  @Test
  void nullPinDigestStillEvaluatesSecondDescriptorArgument() {
    port.pin = new PluginPin("version", null, 1);
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals("descriptor2", port.calls.getLast());
  }

  @Test
  void descriptorsAreNotCached() {
    port.second = descriptor("ignored-second-version", "other");
    assertEquals(
        "PLUGIN_PIN_MISMATCH", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals("descriptor2", port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void fiveOrTermsShortCircuitAtOriginalColumn(int term) {
    if (term == 0) port.first = descriptor("other", "digest");
    if (term == 1) port.second = descriptor("version", "other");
    if (term == 2) port.version = "other";
    if (term == 3) port.digest = "other";
    if (term == 4) port.epoch = 2;
    TaskFailure f = assertThrows(TaskFailure.class, () -> service.access(port));
    assertEquals(409, f.status());
    assertEquals("PLUGIN_PIN_MISMATCH", f.code());
    assertEquals(
        List.of("descriptor1", "descriptor2", "version", "digest", "epoch").get(term),
        port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "digest"})
  void nullStoredStringIsMismatchAtReachedTerm(String field) {
    if (field.equals("version")) port.version = null;
    else port.digest = null;
    assertEquals(
        "PLUGIN_PIN_MISMATCH", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals(field, port.calls.getLast());
  }

  static final class Fixture implements TodoEligibility {
    List<String> calls = new ArrayList<>();
    List<String> caps = List.of("todo_write");
    PluginPin pin = new PluginPin("version", "digest", 1);
    ToolDescriptor first = TodoEligibilityServiceTest.descriptor("version", "digest"),
        second = first;
    String version = "version", digest = "digest", fail;
    long epoch = 1;
    int descriptions;
    boolean missing;
    IllegalStateException failure = new IllegalStateException("original");

    void hit(String name) {
      calls.add(name);
      if (name.equals(fail)) throw failure;
    }

    public List<String> capabilities() {
      hit("caps");
      return caps;
    }

    public PluginPin pin() {
      hit("pin");
      return pin;
    }

    public Installation installation() {
      hit("lock");
      if (missing) throw new TaskFailure(404, "NOT_FOUND");
      return new Installation() {
        public String version() {
          hit("version");
          return version;
        }

        public String digest() {
          hit("digest");
          return digest;
        }

        public long epoch() {
          hit("epoch");
          return epoch;
        }
      };
    }

    public ToolDescriptor descriptor() {
      hit("descriptor" + (++descriptions));
      return descriptions == 1 ? first : second;
    }

    public void projection() {
      hit("projection");
    }
  }
}
