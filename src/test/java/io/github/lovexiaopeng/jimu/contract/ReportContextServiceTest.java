package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ReportContexts;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ReportContextServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 双快照读取、pin短路及 public leaseTx/fence/缺失首错。 */
class ReportContextServiceTest {
  private static final class Records implements ReportContexts, ReportContexts.Task {
    final List<String> calls = new ArrayList<>();
    List<SourceRow> rows = List.of();
    ToolSnapshot first = new ToolSnapshot(1, Map.of());
    ToolSnapshot second = first;
    int reads;
    boolean allowed = true;
    Instant time = Instant.parse("2026-10-27T00:00:00Z");

    public <T> T transaction(Lease lease, Supplier<T> work) {
      calls.add("tx");
      try {
        return work.get();
      } finally {
        calls.add("tx-end");
      }
    }

    public Task fenced(Lease lease, boolean admission) {
      calls.add("fence:" + admission);
      return this;
    }

    public boolean publicReport() {
      calls.add("public");
      return allowed;
    }

    public Instant time() {
      calls.add("time");
      return time;
    }

    public ToolSnapshot tools() {
      calls.add("tools");
      return reads++ == 0 ? first : second;
    }

    public List<SourceRow> sources(Task task, boolean composed) {
      assertSame(this, task);
      calls.add("sources:" + composed);
      return rows;
    }

    SourceRow row(Map<String, String> values) {
      return column -> {
        calls.add(column);
        return values.get(column);
      };
    }
  }

  @Test
  void constructorIsLazy() {
    var r = new Records();
    new ReportContextServiceImpl(r);
    assertTrue(r.calls.isEmpty());
  }

  @Test
  void ordinaryOrderRawStringsAndUnmodifiableList() {
    var r = new Records();
    r.rows =
        List.of(r.row(Map.of("id", "1", "output_snapshot", " 中🙂\n ", "output_digest", "digest")));
    var result = new ReportContextServiceImpl(r).context((Lease) null);
    assertEquals(
        List.of(
            "tx",
            "fence:true",
            "public",
            "time",
            "tools",
            "tools",
            "sources:false",
            "id",
            "output_snapshot",
            "output_digest",
            "time",
            "tx-end"),
        r.calls);
    assertEquals(" 中🙂\n ", result.sources().getFirst().output());
    assertThrows(UnsupportedOperationException.class, () -> result.sources().clear());
  }

  @Test
  void publicMissingShortCircuitsTimeAndTools() {
    var r = new Records();
    r.allowed = false;
    assertEquals(
        "REPORT_METADATA_MISSING",
        assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context((Lease) null))
            .code());
    assertEquals(List.of("tx", "fence:true", "public", "tx-end"), r.calls);
  }

  @Test
  void missingTimeDoesNotReadTools() {
    var r = new Records();
    r.time = null;
    assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context((Lease) null));
    assertEquals(List.of("tx", "fence:true", "public", "time", "tx-end"), r.calls);
  }

  private static Records composed() {
    var r = new Records();
    r.first = new ToolSnapshot(1, Map.of("compose_report", new PluginPin("0.1.0", "compose", 1)));
    return r;
  }

  @Test
  void emptyComposedDoesNotValidateMissingPin() {
    var r = composed();
    var result = new ReportContextServiceImpl(r).context(r);
    assertTrue(result.sources().isEmpty());
    assertEquals(List.of("tools", "tools", "sources:true", "time"), r.calls);
  }

  @Test
  void secondToolsSnapshotSuppliesPinAndSourcesKeepOrder() {
    var r = composed();
    r.second = new ToolSnapshot(1, Map.of("web_fetch", new PluginPin("0.3.0", "pin", 1)));
    r.rows =
        List.of(
            r.row(Map.of("id", "second", "plugin_version", "0.3.0", "plugin_digest", "pin")),
            r.row(Map.of("id", "first", "plugin_version", "0.3.0", "plugin_digest", "pin")));
    var result = new ReportContextServiceImpl(r).context(r);
    assertEquals(
        List.of("second", "first"), result.sources().stream().map(s -> s.invocationId()).toList());
    assertEquals(
        List.of(
            "tools",
            "tools",
            "sources:true",
            "plugin_version",
            "plugin_digest",
            "id",
            "output_snapshot",
            "output_digest",
            "plugin_version",
            "plugin_digest",
            "id",
            "output_snapshot",
            "output_digest",
            "time"),
        r.calls);
  }

  @Test
  void missingPinRejectsBeforeAnySourceGetter() {
    var r = composed();
    r.rows = List.of(r.row(Map.of()));
    assertEquals(
        "REPORT_COMPOSITION_INVALID",
        assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context(r)).code());
    assertEquals(List.of("tools", "tools", "sources:true"), r.calls);
  }

  @Test
  void wrongVersionStopsBeforeDigestAndTime() {
    var r = composed();
    r.second = new ToolSnapshot(1, Map.of("web_fetch", new PluginPin("0.3.0", "pin", 1)));
    r.rows = List.of(r.row(Map.of("plugin_version", "old")));
    assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context(r));
    assertEquals(List.of("tools", "tools", "sources:true", "plugin_version"), r.calls);
  }

  @Test
  void wrongPinVersionDoesNotReadRow() {
    var r = composed();
    r.second = new ToolSnapshot(1, Map.of("web_fetch", new PluginPin("0.2.0", "pin", 1)));
    r.rows = List.of(r.row(Map.of()));
    assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context(r));
    assertEquals(List.of("tools", "tools", "sources:true"), r.calls);
  }

  @Test
  void wrongDigestStopsBeforeSourceConstruction() {
    var r = composed();
    r.second = new ToolSnapshot(1, Map.of("web_fetch", new PluginPin("0.3.0", "pin", 1)));
    r.rows = List.of(r.row(Map.of("plugin_version", "0.3.0", "plugin_digest", "bad")));
    assertThrows(TaskFailure.class, () -> new ReportContextServiceImpl(r).context(r));
    assertEquals(
        List.of("tools", "tools", "sources:true", "plugin_version", "plugin_digest"), r.calls);
  }

  @Test
  void originalSourceExceptionEscapesWithoutTime() {
    var r = new Records();
    var failure = new IllegalStateException("source");
    r.rows =
        List.of(
            column -> {
              throw failure;
            });
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> new ReportContextServiceImpl(r).context(r)));
    assertEquals(List.of("tools", "tools", "sources:false"), r.calls);
  }
}
