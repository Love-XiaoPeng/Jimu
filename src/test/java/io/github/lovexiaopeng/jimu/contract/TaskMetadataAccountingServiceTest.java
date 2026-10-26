package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskMetadataAccounting.Table;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskMetadataAccountingServiceImpl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 完整扫描后拒绝、两个独立排除集、原行与值访问顺序。 */
class TaskMetadataAccountingServiceTest {
  private final TaskMetadataAccountingServiceImpl service = new TaskMetadataAccountingServiceImpl();

  private static final class Records implements TaskMetadataAccounting {
    final List<String> calls = new ArrayList<>();
    final EnumMap<Table, List<Map<String, Object>>> values = new EnumMap<>(Table.class);
    RuntimeException failure;

    public List<Map<String, Object>> rows(Table table) {
      calls.add(table.name());
      if (table == Table.EVENT && failure != null) throw failure;
      return values.getOrDefault(table, List.of());
    }

    public int bytes(String value) {
      calls.add("bytes:" + value.length());
      return value.getBytes(StandardCharsets.UTF_8).length;
    }
  }

  private void run(Records records, boolean directory, boolean closing) {
    if (directory) service.directoryMetadata(records, closing);
    else service.todoMetadata(records, closing);
  }

  @Test
  void exactSeparateTableOrder() {
    var r = new Records();
    service.directoryMetadata(r, false);
    assertEquals(
        List.of(
            "TASK",
            "TASK_DIRECTORY",
            "INVOCATION_PATH_BINDING",
            "TASK_ATTEMPT",
            "STEP",
            "INVOCATION",
            "MODEL_DISPATCH",
            "RESERVATION",
            "EVENT"),
        r.calls);
    r.calls.clear();
    service.todoMetadata(r, false);
    assertEquals(
        List.of(
            "TASK",
            "TASK_TODO_PROJECTION",
            "TASK_ATTEMPT",
            "STEP",
            "INVOCATION",
            "MODEL_DISPATCH",
            "RESERVATION",
            "EVENT"),
        r.calls);
  }

  @ParameterizedTest
  @CsvSource({"true,false,61440", "false,false,61440", "true,true,65536", "false,true,65536"})
  void equalityAllowedAndOneOverRejectedOnlyAfterFinalScan(
      boolean directory, boolean closing, int limit) {
    var r = new Records();
    r.values.put(Table.TASK, List.of(Map.of("x", "x".repeat(limit - 40))));
    run(r, directory, closing);
    assertEquals("EVENT", r.calls.getLast());
    r.values.put(Table.TASK, List.of(Map.of("x", "x".repeat(limit - 39))));
    r.calls.clear();
    var f = assertThrows(TaskFailure.class, () -> run(r, directory, closing));
    assertEquals(directory ? "DIRECTORY_STORAGE_LIMIT" : "TODO_STORAGE_LIMIT", f.code());
    assertEquals("EVENT", r.calls.getLast());
  }

  @Test
  void nullUnicodeAndValueToStringArrival() {
    var r = new Records();
    var row = new LinkedHashMap<String, Object>();
    row.put("null", null);
    row.put("emoji", "中🙂");
    row.put(
        "object",
        new Object() {
          public String toString() {
            r.calls.add("toString");
            return "é";
          }
        });
    r.values.put(Table.TASK, List.of(row));
    service.todoMetadata(r, false);
    assertEquals(
        List.of("TASK", "bytes:3", "toString", "bytes:1", "TASK_TODO_PROJECTION"),
        r.calls.subList(0, 5));
  }

  @Test
  void separateBodyExclusionsAreCompleteAndCaseSensitive() {
    var row = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "request_snapshot",
            "goal",
            "summary",
            "model_snapshot",
            "tools_snapshot",
            "binding_snapshot",
            "response_snapshot",
            "input_snapshot",
            "output_snapshot",
            "usage_snapshot",
            "retained_digests",
            "filesystem_receipt",
            "directory_cwd",
            "cwd",
            "base_cwd",
            "target_cwd",
            "canonical_paths_snapshot",
            "directory_evidence_snapshot")) row.put(key, "x".repeat(65537));
    var r = new Records();
    r.values.put(Table.TASK, List.of(row));
    service.directoryMetadata(r, false);
    assertTrue(r.calls.stream().noneMatch(c -> c.startsWith("bytes:")));
    assertEquals(
        "TODO_STORAGE_LIMIT",
        assertThrows(TaskFailure.class, () -> service.todoMetadata(r, false)).code());
    row.put("GOAL", "x".repeat(65537));
    assertThrows(TaskFailure.class, () -> service.directoryMetadata(r, false));
  }

  @Test
  void nullUtf8ColumnAndRowCostsReachExactOpenLimit() {
    var r = new Records();
    var row = new LinkedHashMap<String, Object>();
    row.put("nullable", null);
    row.put("unicode", "中🙂");
    row.put("scalar", "é");
    row.put("padding", "x".repeat(61440 - 74));
    r.values.put(Table.TASK, List.of(row));
    service.directoryMetadata(r, false);
    service.todoMetadata(r, false);
    row.put("padding", row.get("padding") + "x");
    assertThrows(TaskFailure.class, () -> service.directoryMetadata(r, false));
    assertThrows(TaskFailure.class, () -> service.todoMetadata(r, false));
  }

  @Test
  void lateReadFailurePrecedesLimitAndSameExceptionEscapes() {
    var r = new Records();
    r.values.put(Table.TASK, List.of(Map.of("x", "x".repeat(70000))));
    r.failure = new IllegalStateException("late read");
    assertSame(
        r.failure, assertThrows(IllegalStateException.class, () -> service.todoMetadata(r, false)));
  }

  @Test
  void valueFailureStopsBeforeNextTable() {
    var r = new Records();
    var failure = new IllegalArgumentException("value");
    r.values.put(
        Table.TASK,
        List.of(
            Map.of(
                "x",
                new Object() {
                  public String toString() {
                    throw failure;
                  }
                })));
    assertSame(
        failure,
        assertThrows(IllegalArgumentException.class, () -> service.directoryMetadata(r, true)));
    assertEquals(List.of("TASK"), r.calls);
  }
}
