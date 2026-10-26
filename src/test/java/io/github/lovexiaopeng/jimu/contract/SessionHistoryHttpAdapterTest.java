package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.api.SessionHistoryController;
import io.github.lovexiaopeng.jimu.api.session.dto.SessionEventRequest;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 定向验证HTTP提取后的严格语法、核权顺序及异步原文响应，不替代真实账本QA。 */
class SessionHistoryHttpAdapterTest {
  private final List<String> calls = new ArrayList<>();
  private final Map<String, Object> attributes = new HashMap<>();
  private final String raw = " {\"text\":\"积木\",\"n\":1.00} \n";
  private boolean denied;
  private long deadline;
  private final SessionEventLedger ledger =
      (SessionEventLedger)
          Proxy.newProxyInstance(
              SessionEventLedger.class.getClassLoader(),
              new Class<?>[] {SessionEventLedger.class},
              (proxy, method, args) -> {
                calls.add(method.getName());
                if (denied) throw new TaskFailure(403, "FORBIDDEN");
                switch (method.getName()) {
                  case "authorizeSessionSearch", "authorizeSessionTrace" ->
                      deadline = (long) args[2];
                  case "sessionEvent" -> {
                    assertEquals(-1L, args[2]);
                    assertEquals(0, args[3]);
                    assertEquals(0, args[4]);
                    throw new TaskFailure(400, "TOOL_INPUT_INVALID");
                  }
                  case "sessionSearch", "sessionTrace" -> {
                    assertEquals(deadline, args[3]);
                    assertArrayEquals(
                        new byte[] {1, 2},
                        ((SessionEventLedger.SearchBody) args[2]).read(deadline));
                    return raw;
                  }
                  case "sessionHistory" -> {
                    return raw;
                  }
                  default -> throw new AssertionError(method);
                }
                return null;
              });
  private final SessionHistoryController controller =
      new SessionHistoryController(
          authorization -> {
            calls.add("authenticate");
            return new Tasks.Identity("tenant", "owner");
          },
          ledger);

  private HttpServletRequest request(String query, String type, long length, String transfer) {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getQueryString" -> query;
                  case "getContentType" -> type;
                  case "getContentLengthLong" -> length;
                  case "getHeader" -> transfer;
                  case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                  }
                  default -> throw new AssertionError("Unexpected HTTP access: " + method);
                });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "00",
        "01",
        "+1",
        "-1",
        "1.0",
        "1e0",
        " 1",
        "１",
        "9007199254740992",
        "99999999999999999"
      })
  void invalidSequenceStillReachesLedger(String seq) throws Exception {
    var input = request("before=1", null, 0, null);
    assertEquals(new SessionEventRequest(-1, 0, 0), SessionEventRequest.parse(seq, input));
    assertEquals(
        400,
        assertThrows(TaskFailure.class, () -> controller.event("ok", "task", seq, input)).status());
    assertEquals(List.of("authenticate", "sessionEvent"), calls);
    denied = true;
    assertEquals(
        403,
        assertThrows(TaskFailure.class, () -> controller.event("ok", "task", seq, input)).status());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "before",
        "before=",
        "before=5",
        "before=01",
        "before=1&before=2",
        "after=4&x=0",
        "before=1&",
        "%62efore=1",
        "after=%31",
        "after=1=2"
      })
  void invalidQueryClearsPartialCounts(String query) {
    assertEquals(
        new SessionEventRequest(-1, 0, 0),
        SessionEventRequest.parse("7", request(query, null, 0, null)));
  }

  @Test
  void decimalBoundsAndBodyRejection() {
    assertEquals(
        new SessionEventRequest(9007199254740991L, 4, 0),
        SessionEventRequest.parse("9007199254740991", request("before=4&after=0", null, -1, null)));
    assertEquals(
        new SessionEventRequest(0, 0, 0),
        SessionEventRequest.parse("0", request(null, null, 0, null)));
    assertEquals(
        new SessionEventRequest(-1, 0, 0),
        SessionEventRequest.parse("7", request(null, null, 1, null)));
    assertEquals(
        new SessionEventRequest(-1, 0, 0),
        SessionEventRequest.parse("7", request(null, null, 0, "chunked")));
  }

  @Test
  void metadataAuthorizesBeforeHttpRejection() throws Exception {
    var input = request("", null, 0, null);
    denied = true;
    assertEquals(
        403,
        assertThrows(TaskFailure.class, () -> controller.metadata("ok", "task", input)).status());
    denied = false;
    assertEquals(
        400,
        assertThrows(TaskFailure.class, () -> controller.metadata("ok", "task", input)).status());
    checkResponse(controller.metadata("ok", "task", request(null, null, -1, null)));
  }

  @Test
  void searchAndTraceKeepLimitsAuthorizationAndCallbacks() {
    for (boolean trace : List.of(false, true)) {
      long limit = trace ? 1024 : 2048;
      String authCall = trace ? "authorizeSessionTrace" : "authorizeSessionSearch";
      for (var invalid :
          List.of(
              request(null, "application/json", limit + 1, null),
              request("", "application/json", 0, null),
              request(null, null, 0, null),
              request(null, "broken/", 0, null),
              request(null, "text/plain", 0, null))) {
        for (boolean rejectAuthority : List.of(true, false)) {
          denied = rejectAuthority;
          calls.clear();
          var failure =
              assertThrows(
                  TaskFailure.class,
                  () -> {
                    if (trace) controller.trace("ok", "task", invalid);
                    else controller.search("ok", "task", invalid);
                  });
          assertEquals(rejectAuthority ? 403 : 400, failure.status());
          assertEquals(List.of("authenticate", authCall), calls);
        }
      }
      denied = false;
      var valid = request(null, "application/json;charset=UTF-8", limit, null);
      var result =
          trace ? controller.trace("ok", "task", valid) : controller.search("ok", "task", valid);
      var pending =
          (SessionEventLedger.SearchRequest) attributes.get("jimu.session.search.request");
      assertEquals(deadline, pending.deadlineNanos());
      pending.completed().accept(new byte[] {1, 2});
      checkResponse((ResponseEntity<?>) result.getResult());
      var second =
          trace ? controller.trace("ok", "task", valid) : controller.search("ok", "task", valid);
      var failure = new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
      ((SessionEventLedger.SearchRequest) attributes.get("jimu.session.search.request"))
          .failed()
          .accept(failure);
      assertSame(failure, second.getResult());
    }
  }

  private void checkResponse(ResponseEntity<?> response) {
    assertSame(raw, response.getBody());
    assertEquals(200, response.getStatusCode().value());
    assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
  }
}
