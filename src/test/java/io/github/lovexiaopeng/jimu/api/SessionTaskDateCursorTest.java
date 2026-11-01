package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

class SessionTaskDateCursorTest {
  static byte[] oracle(Tasks.View v) throws Exception {
    var out = new MockHttpOutputMessage();
    new JacksonJsonHttpMessageConverter().write(v, MediaType.APPLICATION_JSON, out);
    return out.getBodyAsBytes();
  }

  static void same(Instant t, long n) throws Exception {
    var h = new SessionTaskNonblockingTest.Harness(true, 0);
    var v = SessionTaskNonblockingTest.view("\u0000\"\\字😀", t, n);
    h.response.hold(v);
    h.response.produced();
    var out = new ByteArrayOutputStream();
    try (var l = h.response.consume(12288);
        var c = new SessionTaskJsonCursor(h.response, l.view())) {
      for (int b; (b = c.next()) >= 0; ) out.write(b);
    }
    if (!Arrays.equals(oracle(v), out.toByteArray())) throw new AssertionError(t);
    h.released();
  }

  static Object field(Object o, String n) throws Exception {
    var f = o.getClass().getDeclaredField(n);
    f.setAccessible(true);
    return f.get(o);
  }

  static void set(Object o, String n, Object v) throws Exception {
    var f = o.getClass().getDeclaredField(n);
    f.setAccessible(true);
    f.set(o, v);
  }

  @org.junit.jupiter.api.Test
  void allDatesPausesBudgetsAndRefusedPendingAreExact() throws Exception {
    int samples = 0;
    for (int day = 0; day < 366; day++) {
      same(
          Instant.parse("2024-01-01T00:00:00Z").plusSeconds(day * 86400L),
          day % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
      samples++;
    }
    for (int h = 0; h < 24; h++) {
      same(Instant.parse("2024-02-29T00:00:00Z").plusSeconds(h * 3600), 0);
      samples++;
    }
    for (int x = 0; x < 60; x++) {
      same(Instant.parse("2024-02-29T23:00:00Z").plusSeconds(x * 60 + x), -1);
      samples++;
    }
    for (var t :
        List.of(
            Instant.MIN,
            Instant.MAX,
            Instant.parse("-0001-12-31T23:59:59.001Z"),
            Instant.parse("0000-02-29T00:00:00Z"),
            Instant.parse("+10000-01-01T00:00:00.000001Z"),
            Instant.parse("1900-03-01T12:34:56.123456789Z"))) {
      same(t, 1);
      samples++;
    }
    same(null, 0);
    samples++;
    byte[] actual =
        java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/test/resources/session-task-response/actual-view.json"));
    var converter = new JacksonJsonHttpMessageConverter();
    var v =
        (Tasks.View)
            converter.read(
                Tasks.View.class, new org.springframework.mock.http.MockHttpInputMessage(actual));
    for (int cut = 0; cut <= actual.length; cut++) {
      var h = new SessionTaskNonblockingTest.Harness(true, 0);
      h.output.allowance = cut;
      h.start(v);
      h.pump.onWritePossible();
      h.output.allowance = Integer.MAX_VALUE;
      h.pump.onWritePossible();
      if (!Arrays.equals(actual, h.output.bytes.toByteArray()) || h.pump.gate.getResult() != null)
        throw new AssertionError("cut " + cut);
      h.released();
    }
    var baseline = new SessionTaskNonblockingTest.Harness(true, 0);
    baseline.output.allowance = Integer.MAX_VALUE;
    baseline.start(v);
    baseline.pump.onWritePossible();
    long cost = baseline.query.resources().operations();
    baseline.released();
    for (int delta : new int[] {-1, 0, 1}) {
      var b = new SessionTaskNonblockingTest.Harness(true, 65536 - cost + delta);
      b.output.allowance = Integer.MAX_VALUE;
      b.start(v);
      b.pump.onWritePossible();
      if ((b.pump.gate.getResult() != null) != (delta > 0))
        throw new AssertionError("full response operation boundary " + delta);
      if (delta > 0 && b.pump.gate.getResult() != b.query.recordedFailure())
        throw new AssertionError("first failure");
      b.released();
    }
    System.out.println("DATE_FULL_RESPONSE cost=" + cost + " N-1/N/N+1 and first slot verified");
    var h = new SessionTaskNonblockingTest.Harness(true, 0);
    h.response.hold(v);
    h.response.produced();
    try (var l = h.response.consume(12288);
        var c = new SessionTaskJsonCursor(h.response, l.view())) {
      set(c, "pending", 0x3132L);
      set(c, "pendingBytes", 2);
      h.query.operation(65534 - h.query.resources().operations());
      try {
        c.next();
        throw new AssertionError("expected refusal");
      } catch (TaskFailure e) {
        if (!"SESSION_HISTORY_LIMIT".equals(e.code())) throw e;
      }
      boolean fixed = true;
      if (!field(c, "pending").equals(fixed ? 0x3132L : 0x31L)
          || !field(c, "pendingBytes").equals(fixed ? 2 : 1))
        throw new AssertionError("ordering unexpected");
      System.out.println(
          "ORDERING "
              + (fixed
                  ? "FIXED pending unchanged 0x3132/2"
                  : "COUNTEREXAMPLE pending mutated 0x3132/2 -> 0x31/1")
              + " at refused encode4; encode completed="
              + h.query.completedWork().get("response.encode"));
    }
    h.released();
    System.out.println(
        "DATE_ORACLE samples="
            + samples
            + " bytePauseCuts="
            + (actual.length + 1)
            + " Jackson3.1.7 identical; local query only");
  }
}
