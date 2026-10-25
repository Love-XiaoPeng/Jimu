package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskCapabilities;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskCapabilitiesServiceImpl;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaskCapabilitiesServiceTest {
  private final TaskCapabilitiesServiceImpl service = new TaskCapabilitiesServiceImpl();

  @Test
  void transactionIdentityThenOneListBeforeAnyGate() {
    var r = new Records("web_fetch");
    assertEquals(List.of("web_fetch"), service.discover(r));
    assertEquals(
        List.of("begin", "identity", "list", "mcp", "questions", "search", "commit"), r.calls);
  }

  @Test
  void identityRefusalNeverReadsListOrFlags() {
    var r = new Records("web_fetch");
    var failure = new TaskFailure(403, "ACCESS_REVOKED");
    r.fail.put("identity", failure);
    assertSame(failure, assertThrows(TaskFailure.class, () -> service.discover(r)));
    assertEquals(List.of("begin", "identity", "rollback"), r.calls);
  }

  @Test
  void emptyListDoesNotReadAnyGate() {
    var r = new Records();
    assertEquals(List.of(), service.discover(r));
    assertEquals(List.of("begin", "identity", "list", "commit"), r.calls);
  }

  @Test
  void queryFailurePropagatesBeforeFlags() {
    var r = new Records();
    var failure = new IllegalStateException("query");
    r.fail.put("list", failure);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> service.discover(r)));
    assertEquals(List.of("begin", "identity", "list", "rollback"), r.calls);
  }

  @Test
  void nullListIsNotNormalized() {
    var r = new Records();
    r.names = null;
    assertThrows(NullPointerException.class, () -> service.discover(r));
    assertEquals(List.of("begin", "identity", "list", "rollback"), r.calls);
  }

  @ParameterizedTest
  @CsvSource({
    "list_mcp_resources,mcp",
    "ask_user_question,questions",
    "working_directory,directory",
    "todo_write,todo",
    "session_event_read,read",
    "session_event_search,session-search",
    "session_event_trace,trace",
    "session_search,prior",
    "glob,search",
    "grep,search",
    "read_image,images"
  })
  void eachRejectionStopsLaterGatesForThatCandidate(String name, String gate) {
    var r = new Records(name, "web_fetch");
    r.flags.put(gate, false);
    assertEquals(List.of("web_fetch"), service.discover(r));
    List<String> expected = new ArrayList<>(List.of("begin", "identity", "list", "mcp"));
    if (!gate.equals("mcp")) {
      expected.add("questions");
      if (!gate.equals("questions")) {
        if (!List.of("search", "images").contains(gate)) expected.add(gate);
        else {
          expected.add("search");
          if (gate.equals("images")) expected.add("images");
        }
      }
    }
    expected.addAll(List.of("mcp", "questions", "search", "commit"));
    assertEquals(expected, r.calls);
  }

  @Test
  void unrelatedCandidateStillInvokesMcpAndSearchBeforeNameChecks() {
    var r = new Records("web_fetch", "custom");
    r.flags.put("mcp", false);
    r.flags.put("search", false);
    assertEquals(List.of("web_fetch", "custom"), service.discover(r));
    assertEquals(
        List.of(
            "begin",
            "identity",
            "list",
            "mcp",
            "questions",
            "search",
            "mcp",
            "questions",
            "search",
            "commit"),
        r.calls);
  }

  @ParameterizedTest
  @CsvSource({"mcp", "search"})
  void supplierFailureForUnrelatedNameIsNotHidden(String gate) {
    var r = new Records("web_fetch");
    var failure = new IllegalArgumentException(gate);
    r.fail.put(gate, failure);
    assertSame(failure, assertThrows(IllegalArgumentException.class, () -> service.discover(r)));
    assertEquals(gate, r.calls.get(r.calls.size() - 2));
    assertEquals("rollback", r.calls.getLast());
  }

  @Test
  void realTimeFlagsAreReadAgainForDuplicateCandidates() {
    var r = new Records("ask_user_question", "ask_user_question");
    r.alternateQuestions = true;
    assertEquals(List.of("ask_user_question"), service.discover(r));
    assertEquals(2, r.questions);
    assertEquals(1, r.calls.stream().filter("search"::equals).count());
  }

  @Test
  void imageSelectionFollowsImagesGateAndRetainsNull() {
    var r = new Records("read_image", "read_image");
    assertEquals(List.of("read_image", "read_image"), service.discover(r));
    assertEquals(
        List.of(
            "begin",
            "identity",
            "list",
            "mcp",
            "questions",
            "search",
            "images",
            "selected",
            "image-model",
            "mcp",
            "questions",
            "search",
            "images",
            "selected",
            "image-model",
            "commit"),
        r.calls);
  }

  @Test
  void imageModelRefusalOnlyFiltersImage() {
    var r = new Records("read_image", "web_fetch");
    r.flags.put("image-model", false);
    assertEquals(List.of("web_fetch"), service.discover(r));
    assertEquals(1, r.calls.stream().filter("selected"::equals).count());
  }

  @Test
  void selectedFailureIsNotCaughtOrConvertedToFalse() {
    var r = new Records("read_image");
    var failure = new TaskFailure(503, "MODEL");
    r.fail.put("selected", failure);
    assertSame(failure, assertThrows(TaskFailure.class, () -> service.discover(r)));
    assertTrue(!r.calls.contains("image-model"));
  }

  @Test
  void originalCandidateOrderDuplicatesAndImmutableResult() {
    var r = new Records("Z", "a", "a", "B");
    var result = service.discover(r);
    assertEquals(r.names, result);
    assertThrows(UnsupportedOperationException.class, () -> result.add("new"));
    assertEquals(4, r.calls.stream().filter("mcp"::equals).count());
    assertEquals(4, r.calls.stream().filter("search"::equals).count());
  }

  @Test
  void nullCandidateWithMcpDisabledFailsBeforeQuestions() {
    var r = new Records((String) null);
    r.flags.put("mcp", false);
    assertThrows(NullPointerException.class, () -> service.discover(r));
    assertEquals(List.of("begin", "identity", "list", "mcp", "rollback"), r.calls);
  }

  @Test
  void nullCandidateWithMcpEnabledReachesOriginalNameEquals() {
    var r = new Records((String) null);
    r.flags.put("questions", true);
    assertThrows(NullPointerException.class, () -> service.discover(r));
    assertEquals(List.of("begin", "identity", "list", "mcp", "questions", "rollback"), r.calls);
  }

  @Test
  void capabilityFailureIsPassedUnchangedToOriginalTransaction() {
    var r = new Records("working_directory");
    var failure = new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
    r.fail.put("directory", failure);
    assertSame(failure, assertThrows(TaskFailure.class, () -> service.discover(r)));
    assertEquals("rollback", r.calls.getLast());
    // The fake records observation only; production retains its special-commit tx implementation.
  }

  private static final class Records implements TaskCapabilities {
    List<String> names;
    final List<String> calls = new ArrayList<>();
    final Map<String, Boolean> flags = new HashMap<>();
    final Map<String, RuntimeException> fail = new HashMap<>();
    boolean alternateQuestions;
    int questions;

    Records(String... names) {
      this.names = Arrays.asList(names);
    }

    boolean get(String name) {
      calls.add(name);
      if (fail.containsKey(name)) throw fail.get(name);
      return flags.getOrDefault(name, true);
    }

    public <T> T transaction(Supplier<T> work) {
      calls.add("begin");
      try {
        T result = work.get();
        calls.add("commit");
        return result;
      } catch (RuntimeException failure) {
        calls.add("rollback");
        throw failure;
      }
    }

    public void identity() {
      get("identity");
    }

    public List<String> installed() {
      get("list");
      return names;
    }

    public boolean mcpEnabled() {
      return get("mcp");
    }

    public boolean questionsEnabled() {
      boolean value = get("questions");
      questions++;
      return alternateQuestions ? questions == 1 : value;
    }

    public boolean directoryAvailable() {
      return get("directory");
    }

    public boolean todoAvailable() {
      return get("todo");
    }

    public boolean sessionAvailable(boolean search) {
      return get(search ? "session-search" : "read");
    }

    public boolean traceAvailable() {
      return get("trace");
    }

    public boolean priorAvailable() {
      return get("prior");
    }

    public boolean searchAvailable() {
      return get("search");
    }

    public boolean imagesAvailable() {
      return get("images");
    }

    public ModelSnapshot selected() {
      get("selected");
      return null;
    }

    public boolean imageModelEnabled(ModelSnapshot model) {
      assertSame(null, model);
      return get("image-model");
    }
  }
}
