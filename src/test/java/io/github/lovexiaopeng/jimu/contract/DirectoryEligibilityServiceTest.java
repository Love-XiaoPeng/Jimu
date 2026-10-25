package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryEligibility;
import io.github.lovexiaopeng.jimu.kernel.directory.service.impl.DirectoryEligibilityServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectoryEligibilityServiceTest {
  private static final List<String> CAPS = List.of("write", "working_directory", "edit", "read");
  private final DirectoryEligibilityServiceImpl service = new DirectoryEligibilityServiceImpl();
  private final Fixture port = new Fixture();

  private static ToolDescriptor descriptor(String version, String digest) {
    return new ToolDescriptor("id", version, "cap", "{}", "{}", digest, "entry");
  }

  @Test
  void originalOrderAndDistinctCapabilitiesReadsEndWithOneProjection() {
    service.access(port);
    var expected = new ArrayList<String>(List.of("caps1", "caps2"));
    for (String name : CAPS)
      expected.addAll(
          List.of(
              "pin:" + name, "lock:" + name, "descriptor:" + name, "version", "digest", "epoch"));
    expected.add("projection");
    assertEquals(expected, port.calls);
  }

  @Test
  void setEqualityAllowsDuplicatesAndLoopRepeatsThem() {
    port.first = List.of("read", "read", "write", "edit", "working_directory");
    port.second = port.first;
    service.access(port);
    assertEquals(2, port.calls.stream().filter("lock:read"::equals).count());
  }

  @Test
  void secondCapabilitiesReadIsNotCachedOrRevalidated() {
    port.second = List.of("read", "read");
    service.access(port);
    assertEquals(2, port.calls.stream().filter("lock:read"::equals).count());
    assertEquals(1, port.calls.stream().filter("projection"::equals).count());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "read,write,edit",
        "read,write,edit,other",
        "read,write,edit,working_directory,other"
      })
  void originalSetRejectionPrecedesSecondRead(String value) {
    port.first = Arrays.asList(value.split(","));
    assertEquals(
        "SNAPSHOT_CORRUPT", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals(List.of("caps1"), port.calls);
  }

  @Test
  void nullInitialCapabilitiesFailBeforeAnyLock() {
    port.first = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(List.of("caps1"), port.calls);
  }

  @Test
  void nullSecondCapabilitiesAreNotReplacedWithInitialList() {
    port.second = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(List.of("caps1", "caps2"), port.calls);
  }

  @Test
  void toolsFailurePrecedesInstallationAndDescriptor() {
    port.failure = "pin:write";
    assertEquals(
        "pin:write",
        assertThrows(IllegalArgumentException.class, () -> service.access(port)).getMessage());
    assertEquals(List.of("caps1", "caps2", "pin:write"), port.calls);
  }

  @Test
  void nullPinStillLocksAndConstructsDescriptor() {
    port.pin = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals(
        List.of("caps1", "caps2", "pin:write", "lock:write", "descriptor:write"), port.calls);
  }

  @Test
  void missingInstallationPrecedesDescriptorAndNullPin() {
    port.pin = null;
    port.missing = true;
    assertEquals("NOT_FOUND", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals("lock:write", port.calls.getLast());
  }

  @Test
  void descriptorFailurePrecedesNullPin() {
    port.pin = null;
    port.failure = "descriptor:write";
    assertEquals(
        "descriptor:write",
        assertThrows(IllegalArgumentException.class, () -> service.access(port)).getMessage());
  }

  @Test
  void nullDescriptorPropagatesAfterLock() {
    port.descriptor = null;
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals("descriptor:write", port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void fiveComparisonsKeepOriginalShortCircuit(int stage) {
    if (stage == 0) port.descriptor = descriptor("other", "digest");
    if (stage == 1) port.descriptor = descriptor("version", "other");
    if (stage == 2) port.version = "other";
    if (stage == 3) port.digest = "other";
    if (stage == 4) port.epoch = 9;
    var failure = assertThrows(TaskFailure.class, () -> service.access(port));
    assertEquals(409, failure.status());
    assertEquals("PLUGIN_PIN_MISMATCH", failure.code());
    assertEquals(
        stage < 2 ? "descriptor:write" : stage == 2 ? "version" : stage == 3 ? "digest" : "epoch",
        port.calls.getLast());
  }

  @Test
  void nullVersionReceiverDoesNotAdvanceToInstallationValues() {
    port.pin = new PluginPin(null, "digest", 1);
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals("descriptor:write", port.calls.getLast());
  }

  @Test
  void nullDigestReceiverDoesNotAdvanceToInstallationValues() {
    port.pin = new PluginPin("version", null, 1);
    assertThrows(NullPointerException.class, () -> service.access(port));
    assertEquals("descriptor:write", port.calls.getLast());
  }

  @Test
  void nullStoredVersionIsOriginalMismatch() {
    port.version = null;
    assertEquals(
        "PLUGIN_PIN_MISMATCH", assertThrows(TaskFailure.class, () -> service.access(port)).code());
    assertEquals("version", port.calls.getLast());
  }

  @Test
  void lazyStoredValueFailurePropagatesAtOriginalPosition() {
    port.failure = "epoch";
    assertEquals(
        "epoch",
        assertThrows(IllegalArgumentException.class, () -> service.access(port)).getMessage());
    assertEquals("epoch", port.calls.getLast());
  }

  @Test
  void originalProjectionFailureIsNotCaughtOrCalledEarly() {
    port.failure = "projection";
    assertEquals(
        "projection",
        assertThrows(IllegalArgumentException.class, () -> service.access(port)).getMessage());
    assertEquals(4, port.calls.stream().filter(v -> v.startsWith("lock:")).count());
    assertEquals("projection", port.calls.getLast());
  }

  private static final class Fixture implements DirectoryEligibility {
    private final List<String> calls = new ArrayList<>();
    private List<String> first = CAPS;
    private List<String> second = CAPS;
    private int reads;
    private String failure;
    private boolean missing;
    private PluginPin pin = new PluginPin("version", "digest", 1);
    private ToolDescriptor descriptor = descriptor("version", "digest");
    private String version = "version";
    private String digest = "digest";
    private long epoch = 1;

    private void hit(String name) {
      calls.add(name);
      if (name.equals(failure)) throw new IllegalArgumentException(name);
    }

    public List<String> capabilities() {
      hit("caps" + (++reads));
      return reads == 1 ? first : second;
    }

    public PluginPin pin(String name) {
      hit("pin:" + name);
      return pin;
    }

    public Installation installation(String name) {
      hit("lock:" + name);
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

    public ToolDescriptor directoryDescriptor() {
      hit("descriptor:working_directory");
      return descriptor;
    }

    public ToolDescriptor fileDescriptor(String name) {
      hit("descriptor:" + name);
      return descriptor;
    }

    public void projection() {
      hit("projection");
    }
  }
}
