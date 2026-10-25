package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.todo.port.TodoAvailability;
import io.github.lovexiaopeng.jimu.kernel.todo.service.impl.TodoAvailabilityServiceImpl;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TodoAvailabilityServiceTest {
  private final TodoAvailabilityServiceImpl service = new TodoAvailabilityServiceImpl();
  private final Fixture port = new Fixture();

  @Test
  void originalModelQueryAndSeparateDescriptorOrder() {
    assertTrue(service.available(port));
    assertEquals(
        List.of(
            "enabled",
            "selected",
            "require",
            "sql",
            "size",
            "row0",
            "descriptor1",
            "version",
            "descriptor2",
            "digest",
            "business",
            "scope",
            "run"),
        port.calls);
  }

  @Test
  void disabledAvoidsEverythingElse() {
    port.enabled = false;
    assertFalse(service.available(port));
    assertEquals(List.of("enabled"), port.calls);
  }

  @Test
  void flagTaskFailureStaysOutsideCatch() {
    port.failAt = "enabled";
    port.failure = new TaskFailure(403, "FLAG");
    assertSame(port.failure, assertThrows(TaskFailure.class, () -> service.available(port)));
  }

  @Test
  void emptyRowsUseOriginalNotFoundToFalseBeforeDescriptor() {
    port.count = 0;
    assertFalse(service.available(port));
    assertEquals(List.of("enabled", "selected", "require", "sql", "size"), port.calls);
  }

  @Test
  void multipleRowsKeepFirstAndNeverReadSecond() {
    port.count = 2;
    assertTrue(service.available(port));
    assertEquals(1, port.calls.stream().filter("row0"::equals).count());
  }

  @Test
  void versionMismatchSkipsSecondDescriptorAndDigestCast() {
    port.version = "other";
    port.digest = 42;
    assertFalse(service.available(port));
    assertEquals("version", port.calls.getLast());
  }

  @Test
  void nullInstalledVersionIsMismatchWithoutReadingDigest() {
    port.version = null;
    assertFalse(service.available(port));
    assertEquals("version", port.calls.getLast());
  }

  @Test
  void nullInstalledDigestIsMismatch() {
    port.digest = null;
    assertFalse(service.available(port));
    assertEquals("digest", port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "digest"})
  void originalStringCastsPropagateOnlyWhenReached(String field) {
    if (field.equals("version")) port.version = 1;
    else port.digest = 1;
    assertThrows(ClassCastException.class, () -> service.available(port));
    assertEquals(field, port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"descriptor1", "descriptor2"})
  void nullDescriptorPropagatesBeforeItsInstalledFieldRead(String point) {
    port.nullDescriptor = point;
    assertThrows(NullPointerException.class, () -> service.available(port));
    assertEquals(point, port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "digest"})
  void nullDescriptorReceiverStillEvaluatesInstalledArgumentFirst(String field) {
    port.nullReceiver = field;
    assertThrows(NullPointerException.class, () -> service.available(port));
    assertEquals(field, port.calls.getLast());
    port.calls.clear();
    port.descriptors = 0;
    if (field.equals("version")) port.version = 42;
    else port.digest = 42;
    assertThrows(ClassCastException.class, () -> service.available(port));
    assertEquals(field, port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "selected",
        "require",
        "sql",
        "size",
        "row0",
        "descriptor1",
        "version",
        "descriptor2",
        "digest",
        "business",
        "scope",
        "run"
      })
  void taskFailureInsideOriginalTryReturnsFalse(String point) {
    port.failAt = point;
    port.failure = new TaskFailure(409, "ORIGINAL");
    assertFalse(service.available(port));
    assertEquals(point, port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "enabled",
        "selected",
        "require",
        "sql",
        "size",
        "row0",
        "descriptor1",
        "version",
        "descriptor2",
        "digest",
        "business",
        "scope",
        "run"
      })
  void otherRuntimeErrorsAreNotSwallowed(String point) {
    port.failAt = point;
    port.failure = new IllegalStateException(point);
    assertSame(
        port.failure, assertThrows(IllegalStateException.class, () -> service.available(port)));
    assertEquals(point, port.calls.getLast());
  }

  @Test
  void digestMismatchSkipsAllTailPolicyReads() {
    port.digest = "other";
    assertFalse(service.available(port));
    assertEquals("digest", port.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"business", "scope", "run"})
  void tailPolicyPresenceShortCircuitsInOriginalOrder(String point) {
    if (point.equals("business")) port.business = new Object();
    if (point.equals("scope")) port.scope = new Object();
    if (point.equals("run")) port.run = "id";
    assertFalse(service.available(port));
    assertEquals(point, port.calls.getLast());
  }

  @Test
  void emptyRunIdIsPresent() {
    port.run = "";
    assertFalse(service.available(port));
    assertEquals("run", port.calls.getLast());
  }

  @Test
  void modelInternalPolicyReadsDoNotReplaceSeparateTailReads() {
    port.internalPolicies = true;
    assertTrue(service.available(port));
    assertEquals(
        List.of("business", "run", "scope", "business", "scope", "run"),
        port.calls.stream().filter(List.of("business", "scope", "run")::contains).toList());
  }

  private static final class Fixture implements TodoAvailability {
    final List<String> calls = new ArrayList<>();
    boolean enabled = true;
    int count = 1;
    int descriptors;
    Object version = "v";
    Object digest = "d";
    String failAt;
    String nullDescriptor;
    String nullReceiver;
    RuntimeException failure;
    Object business;
    Object scope;
    String run;
    boolean internalPolicies;

    void read(String point) {
      calls.add(point);
      if (point.equals(failAt)) throw failure;
    }

    public boolean enabled() {
      read("enabled");
      return enabled;
    }

    public ModelSnapshot selected() {
      read("selected");
      return null;
    }

    public void requireTodo(ModelSnapshot model) {
      assertSame(null, model);
      read("require");
      if (internalPolicies) {
        businessPolicyPresent();
        evaluationRunIdPresent();
        scopeRequirementPresent();
      }
    }

    public List<Installation> installed() {
      read("sql");
      return new AbstractList<>() {
        public int size() {
          read("size");
          return count;
        }

        public Installation get(int index) {
          assertEquals(0, index);
          read("row0");
          return new Installation() {
            public String version() {
              read("version");
              return (String) version;
            }

            public String digest() {
              read("digest");
              return (String) digest;
            }
          };
        }
      };
    }

    public boolean businessPolicyPresent() {
      read("business");
      return business != null;
    }

    public boolean scopeRequirementPresent() {
      read("scope");
      return scope != null;
    }

    public boolean evaluationRunIdPresent() {
      read("run");
      return run != null;
    }

    public ToolDescriptor descriptor() {
      String point = "descriptor" + ++descriptors;
      read(point);
      if (point.equals(nullDescriptor)) return null;
      // Different descriptors prove the second call supplies digest, without caching.
      return new ToolDescriptor(
          "id",
          "version".equals(nullReceiver) ? null : descriptors == 1 ? "v" : "ignored",
          "todo_write",
          "{}",
          "{}",
          "digest".equals(nullReceiver) ? null : descriptors == 2 ? "d" : "ignored",
          "entry");
    }
  }
}
