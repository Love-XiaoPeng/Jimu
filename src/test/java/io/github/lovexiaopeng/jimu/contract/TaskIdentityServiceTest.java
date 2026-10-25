package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskIdentityRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskIdentityServiceImpl;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TaskIdentityServiceTest {
  private final TaskIdentityServiceImpl service = new TaskIdentityServiceImpl();
  private final Identity identity = new Identity("tenant", "actor");
  private final Fixture records = new Fixture();

  private static Stream<Arguments> nonTrue() {
    return Stream.of(false, null, "true", 1, 1L).map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("nonTrue")
  void actorRequiresOriginalBooleanTrueBeforeCapabilities(Object enabled) {
    records.actorEnabled = enabled;
    var failure = assertThrows(TaskFailure.class, () -> service.epoch(records, identity, null));
    assertEquals(403, failure.status());
    assertEquals("ACCESS_REVOKED", failure.code());
    assertEquals(List.of("actor", "actor.empty", "actor.first", "actor.enabled"), records.calls);
  }

  @Test
  void emptyActorShortCircuitsFirstAndSorting() {
    records.actorEmpty = true;
    assertEquals(
        "ACCESS_REVOKED",
        assertThrows(TaskFailure.class, () -> service.epoch(records, identity, null)).code());
    assertEquals(List.of("actor", "actor.empty"), records.calls);
  }

  @Test
  void identityReadsOnlyFirstEnabledOnce() {
    service.identity(records, identity);
    assertEquals(List.of("actor", "actor.empty", "actor.first", "actor.enabled"), records.calls);
  }

  @Test
  void nullCapabilitiesFailAfterSuccessfulActorLock() {
    assertThrows(NullPointerException.class, () -> service.epoch(records, identity, null));
    assertEquals(List.of("actor", "actor.empty", "actor.first", "actor.enabled"), records.calls);
  }

  @Test
  void nullElementSortingFinishesOrFailsBeforeFirstInstallation() {
    assertThrows(
        NullPointerException.class,
        () -> service.epoch(records, identity, Arrays.asList("z", null, "a")));
    assertFalse(records.calls.stream().anyMatch(s -> s.startsWith("lock:")));
    assertEquals("actor.enabled", records.calls.getLast());
  }

  @Test
  void emptyCapabilitiesStillAuthenticateAndReturnZero() {
    assertEquals(0L, service.epoch(records, identity, List.of()));
    assertEquals(List.of("actor", "actor.empty", "actor.first", "actor.enabled"), records.calls);
  }

  @Test
  void completeNaturalSortKeepsDuplicatesAndBlankAndRereadsFirst() {
    var input = new ArrayList<>(List.of("z", "a", "z", ""));
    assertEquals(4L, service.epoch(records, identity, input));
    assertEquals(List.of("z", "a", "z", ""), input);
    assertEquals(
        List.of("lock:", "lock:a", "lock:z", "lock:z"),
        records.calls.stream().filter(s -> s.startsWith("lock:")).toList());
    assertEquals(2, records.calls.stream().filter("a.first"::equals).count());
    assertEquals(4, records.calls.stream().filter("z.first"::equals).count());
    assertEquals(
        List.of("a.empty", "a.first", "a.enabled", "a.first", "a.epoch"),
        records.calls.stream().filter(s -> s.startsWith("a.")).toList());
  }

  @ParameterizedTest
  @MethodSource("nonTrue")
  void disabledInstallationDoesNotReadEpochOrLockNext(Object enabled) {
    records.flags.put("a", enabled);
    records.epochs.put("a", "invalid-but-unread");
    var failure =
        assertThrows(TaskFailure.class, () -> service.epoch(records, identity, List.of("z", "a")));
    assertEquals(403, failure.status());
    assertEquals("CAPABILITY_REVOKED", failure.code());
    assertEquals("a.enabled", records.calls.getLast());
    assertEquals(1, records.calls.stream().filter("a.first"::equals).count());
    assertFalse(records.calls.contains("lock:z"));
  }

  @Test
  void missingInstallationDoesNotReadFirstOrEpoch() {
    records.missing = "a";
    assertEquals(
        "CAPABILITY_REVOKED",
        assertThrows(TaskFailure.class, () -> service.epoch(records, identity, List.of("z", "a")))
            .code());
    assertEquals("a.empty", records.calls.getLast());
    assertFalse(records.calls.contains("a.first"));
  }

  @Test
  void nullEpochFailsOnlyAfterSecondFirstAndBeforeNextLock() {
    records.epochs.put("a", null);
    assertThrows(
        NullPointerException.class, () -> service.epoch(records, identity, List.of("z", "a")));
    assertEquals("a.epoch", records.calls.getLast());
    assertEquals(2, records.calls.stream().filter("a.first"::equals).count());
    assertFalse(records.calls.contains("lock:z"));
  }

  @Test
  void nonNumberEpochPropagatesOriginalCastFailure() {
    records.epochs.put("a", "1");
    assertThrows(
        ClassCastException.class, () -> service.epoch(records, identity, List.of("a", "z")));
    assertEquals("a.epoch", records.calls.getLast());
    assertFalse(records.calls.contains("lock:z"));
  }

  private static Stream<Arguments> numbers() {
    return Stream.of(
            (byte) 3,
            (short) -2,
            9,
            Long.MIN_VALUE,
            new BigInteger("18446744073709551617"),
            new BigDecimal("4.9"),
            -3.5d)
        .map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("numbers")
  void originalNumberLongValueIsNotNormalized(Number number) {
    records.epochs.put("a", number);
    assertEquals(number.longValue(), service.epoch(records, identity, List.of("a")));
  }

  @Test
  void signedLongOverflowIsPreserved() {
    records.epochs.put("a", Long.MAX_VALUE);
    records.epochs.put("b", 1L);
    assertEquals(Long.MIN_VALUE, service.epoch(records, identity, List.of("b", "a")));
  }

  @Test
  void negativeValuesAndRepeatedLongValueCallsRemainObservable() {
    records.epochs.put("a", -2L);
    assertEquals(-4L, service.epoch(records, identity, List.of("a", "a")));
    assertEquals(2, records.calls.stream().filter("a.epoch"::equals).count());
  }

  private final class Fixture implements TaskIdentityRecords {
    final List<String> calls = new ArrayList<>();
    Object actorEnabled = true;
    boolean actorEmpty;
    String missing;
    final Map<String, Object> flags = new HashMap<>();
    final Map<String, Object> epochs = new HashMap<>();

    public Rows<Actor> lockActor(Identity value) {
      assertEquals(identity, value);
      calls.add("actor");
      return new Rows<>() {
        public boolean isEmpty() {
          calls.add("actor.empty");
          return actorEmpty;
        }

        public Actor first() {
          calls.add("actor.first");
          return () -> {
            calls.add("actor.enabled");
            return actorEnabled;
          };
        }
      };
    }

    public Rows<Installation> lockInstallation(Identity value, String capability) {
      assertEquals(identity, value);
      calls.add("lock:" + capability);
      return new Rows<>() {
        public boolean isEmpty() {
          calls.add(capability + ".empty");
          return capability.equals(missing);
        }

        public Installation first() {
          calls.add(capability + ".first");
          return new Installation() {
            public Object enabled() {
              calls.add(capability + ".enabled");
              return flags.getOrDefault(capability, true);
            }

            public Number epoch() {
              calls.add(capability + ".epoch");
              return (Number) epochs.getOrDefault(capability, 1L);
            }
          };
        }
      };
    }
  }
}
