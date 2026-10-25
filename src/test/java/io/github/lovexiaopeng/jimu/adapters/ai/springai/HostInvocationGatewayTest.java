package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionCommitUnconfirmed;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.todo.TodoContracts;
import io.github.lovexiaopeng.jimu.adapters.runtime.web.FetchRuntime;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Step;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TodoLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import io.github.lovexiaopeng.jimu.plugin.api.TodoPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessResourceFailureException;

/** 真实 advisor 回调与冻结响应重放；账本为内存替身，不声称数据库验收。 */
class HostInvocationGatewayTest {
  private static final String TODO =
      "{\"todos\":[{\"content\":\"保留原字节 😀\",\"status\":\"pending\"}]}";
  private static final String QUESTION =
      "{\"questions\":[{\"id\":\"q\",\"question\":\"Choose\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"multi_select\":false}]}";
  @TempDir Path root;

  @Test
  void realAdvisorDispatchesRepeatedToolSlotsInOrderAndPreservesReplies() throws Exception {
    var fixture = new Fixture("todo_write", TODO);
    fixture.run("");
    assertEquals(fixture.calls, fixture.executed);
    assertEquals(List.of("step-1", "step-1"), fixture.steps);
    assertEquals(2, fixture.prepared.size());
    var replies = fixture.prepared.get(1).messages().getLast().replies();
    assertEquals(
        List.of("provider-1", "provider-2"),
        replies.stream().map(DurableAgent.ToolReply::id).toList());
    assertEquals(
        List.of(Payloads.json(fixture.todoResult), Payloads.json(fixture.todoResult)),
        replies.stream().map(DurableAgent.ToolReply::data).toList());
    assertTrue(fixture.finished);
  }

  @Test
  void committedQuestionPauseReachesAttemptBoundaryWithoutReplyOrNextModelStep() throws Exception {
    var fixture = new Fixture("ask_user_question", QUESTION);
    fixture.run("QUESTION_PAUSED");
    assertTrue(Files.readString(root.resolve("reached")).startsWith("QUESTION_PAUSED task "));
    assertEquals(List.of(fixture.calls.getFirst()), fixture.executed);
    assertEquals(1, fixture.prepared.size());
    assertFalse(fixture.finished);
  }

  @Test
  void unconfirmedTodoCommitReachesAttemptBoundaryWithoutReplyOrRetry() throws Exception {
    var fixture = new Fixture("todo_write", TODO);
    fixture.failure = new DataAccessResourceFailureException("synthetic commit uncertainty");
    fixture.run("TODO_UNCONFIRMED");
    assertTrue(Files.readString(root.resolve("reached")).startsWith("TODO_UNCONFIRMED task "));
    assertEquals(List.of(fixture.calls.getFirst()), fixture.executed);
    assertEquals(1, fixture.prepared.size());
    assertFalse(fixture.finished);
  }

  @Test
  void sessionCommitUnconfirmedIsPropagatedUnchanged() throws Exception {
    var fixture = new Fixture("session_event_read", "{\"seq\":1}");
    var failure = new SessionCommitUnconfirmed();
    fixture.failure = failure;
    assertSame(
        failure,
        assertThrows(
            SessionCommitUnconfirmed.class,
            () ->
                fixture
                    .gateway()
                    .invoke(
                        fixture.lease, "original-step", fixture.calls.getFirst(), fixture.input)));
    assertEquals(List.of("original-step"), fixture.steps);
  }

  @Test
  void mismatchedInputNeverConsumesPersistedSlot() throws Exception {
    var fixture = new Fixture("todo_write", TODO);
    assertEquals(
        "INVOCATION_MISMATCH",
        assertThrows(
                TaskFailure.class,
                () ->
                    fixture
                        .gateway()
                        .invoke(
                            fixture.lease, "original-step", fixture.calls.getFirst(), TODO + " "))
            .code());
    assertTrue(fixture.executed.isEmpty());
  }

  private final class Fixture implements InvocationHandler {
    final String input;
    final Lease lease;
    final ToolDescriptor descriptor;
    final List<Call> calls;
    final List<Call> executed = new ArrayList<>();
    final List<String> steps = new ArrayList<>();
    final List<DurableAgent.Checkpoint> prepared = new ArrayList<>();
    final TodoPort.Result todoResult = new TodoContracts().input(TODO);
    final ExecutionLedger ledger;
    final ModelProfiles models;
    final FetchRuntime fetch;
    RuntimeException failure;
    boolean finished;

    Fixture(String tool, String input) throws Exception {
      this.input = input;
      lease =
          new Lease(
              "tenant",
              "actor",
              "task",
              "attempt",
              17,
              "worker",
              Instant.now().plusSeconds(60),
              "frozen-response gateway test",
              List.of(tool),
              7);
      descriptor =
          switch (tool) {
            case "todo_write" -> new TodoContracts().descriptor();
            case "ask_user_question" -> new QuestionContracts().contract("0.1.0").descriptor();
            case "session_event_read" -> new SessionContracts(1).descriptor();
            default -> throw new AssertionError(tool);
          };
      calls =
          List.of(
              new Call("0:provider-1", "provider-1", tool, input),
              new Call("1:provider-2", "provider-2", tool, input));
      ledger =
          (ExecutionLedger)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {
                    ExecutionLedger.class,
                    TodoLedger.class,
                    UserQuestionLedger.class,
                    SessionEventLedger.class
                  },
                  this);
      var env = new StandardEnvironment();
      env.getPropertySources().remove("systemEnvironment");
      env.getPropertySources().remove("systemProperties");
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "gateway-test",
                  Map.of(
                      "jimu.test-mode",
                      "true",
                      "jimu.provider-origin",
                      "http://127.0.0.1:1",
                      "jimu.fixture.credentials",
                      "tenant=synthetic-test-token")));
      models = new ModelProfiles(env);
      assertFalse(models.selected("tenant").paid());
      fetch =
          new FetchRuntime(
              new BuiltinManifestLoader().load(), URI.create("http://127.0.0.1:1"), Map.of());
    }

    HostInvocationGateway gateway() throws Exception {
      return new HostInvocationGateway(
          ledger, null, fetch, new BoundaryProbe("", root), null, null);
    }

    void run(String boundary) throws Exception {
      Files.writeString(root.resolve("release"), "release");
      try (var agent =
          new DurableAgent(ledger, null, fetch, new BoundaryProbe(boundary, root), models)) {
        agent.run(lease);
      }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (args != null && args.length > 0 && args[0] instanceof Lease) assertSame(lease, args[0]);
      return switch (method.getName()) {
        case "toolSnapshot" ->
            new ToolSnapshot(
                1,
                Map.of(
                    lease.capabilities().getFirst(),
                    new PluginPin(descriptor.version(), descriptor.outputSha256(), 7)));
        case "modelSnapshot" -> models.selected("tenant");
        case "checkpoint" -> Optional.empty();
        case "pendingQuestionMessages" -> List.of();
        case "prepare" -> {
          prepared.add(Payloads.read((String) args[1], DurableAgent.Checkpoint.class));
          int ordinal = prepared.size();
          var response =
              new DurableAgent.Response(
                  2,
                  ordinal == 1 ? "" : "done",
                  ordinal == 1
                      ? calls.stream()
                          .map(
                              c ->
                                  new DurableAgent.ToolCall(
                                      c.providerId(), "function", c.tool(), c.input()))
                          .toList()
                      : List.of(),
                  null,
                  null);
          yield new Step(
              "step-" + ordinal,
              ordinal,
              (String) args[1],
              Payloads.json(response),
              "original-dispatch");
        }
        case "replaceOrResult", "openOrResult", "readOrResult" -> {
          var call = (Call) args[2];
          assertEquals(input, call.input());
          assertEquals(descriptor.version(), args[3]);
          assertEquals(descriptor.outputSha256(), args[4]);
          executed.add(call);
          steps.add((String) args[1]);
          if (failure != null) throw failure;
          yield method.getName().equals("openOrResult")
              ? new UserQuestionLedger.OpenResult(true, null)
              : todoResult;
        }
        case "finish" -> {
          assertEquals("done", args[1]);
          finished = true;
          yield null;
        }
        default -> throw new AssertionError("Unexpected ledger operation: " + method.getName());
      };
    }
  }
}
