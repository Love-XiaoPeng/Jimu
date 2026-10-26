package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords;
import io.github.lovexiaopeng.jimu.kernel.model.service.impl.MaterialEvidenceServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original codec views, explicit per-I/O port, first-error/short-circuit and receipt rules. */
class MaterialEvidenceServiceTest {
  private final io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease lease =
      new io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease(
          "tenant",
          "actor",
          "task",
          "attempt",
          1,
          "worker",
          Instant.parse("2026-10-27T00:00:00Z"),
          "goal",
          List.of(),
          1);
  private final Map<String, Object> row = new HashMap<>();
  private final List<String> calls = new ArrayList<>();
  private List<MaterialEvidenceRecords.Row> steps = List.of(),
      writes = List.of(),
      history = List.of();
  private final MapperMaterialEvidenceRecords codecs = new MapperMaterialEvidenceRecords(null);
  private final MaterialEvidenceRecords port =
      (MaterialEvidenceRecords)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {MaterialEvidenceRecords.class},
              (p, m, a) -> {
                calls.add(m.getName());
                return switch (m.getName()) {
                  case "leaseTransaction" -> ((Supplier<?>) a[1]).get();
                  case "fenced" -> {
                    assertEquals(true, a[1]);
                    yield MapperMaterialEvidenceRecords.wrap(row);
                  }
                  case "successfulMaterialWrite" -> writes;
                  case "existingMaterialStep", "materialPrecedingSteps" -> steps;
                  case "materialSources" -> List.of();
                  case "materialFileHistory" -> history;
                  case "bindMaterialReceipt" -> {
                    row.put("material_file_snapshot", a[0]);
                    row.put("material_file_digest", a[1]);
                    yield 0;
                  }
                  case "businessTask" -> false;
                  default -> {
                    try {
                      yield m.invoke(codecs, a);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                };
              });
  private final MaterialEvidenceServiceImpl service = new MaterialEvidenceServiceImpl(port);

  private EvaluationMaterials.Material seed(String sample) {
    var plan = EvaluationPlans.plan("qwen-main-v1");
    var material = EvaluationMaterials.load(plan, sample);
    var binding =
        new EvaluationPlan.Binding(
            plan.id(),
            Payloads.hash(Payloads.json(plan)),
            "A",
            sample,
            "local",
            null,
            plan.maxRequests(),
            plan.maxCostMicros());
    row.put("evaluation_snapshot", Payloads.json(binding));
    row.put("material_snapshot", Payloads.json(material));
    row.put("material_digest", Payloads.hash(Payloads.json(material)));
    row.put("goal", material.goal());
    row.put(
        "report_time",
        java.time.LocalDateTime.ofInstant(
            Instant.parse("2026-10-26T00:00:00Z"), java.time.ZoneOffset.UTC));
    row.put(
        "material_system_digest",
        Payloads.hash(
            material
                .systemTemplate()
                .replace("{{HOST_REPORT_TIME}}", JdbcLedger.time(row, "report_time").toString())));
    row.put("tenant_id", "tenant");
    row.put("id", "task");
    row.put("record_version", 1);
    return material;
  }

  private void mismatch(Runnable work) {
    assertEquals("EVALUATION_MATERIAL_MISMATCH", assertThrows(TaskFailure.class, work::run).code());
  }

  @Test
  void nullableHistoryIsUnwrappedWithoutReadingBeforeProtocolModel() {
    var error =
        assertThrows(
            NullPointerException.class, () -> codecs.project(null, null, List.of(), ref -> null));
    assertTrue(error.getMessage().contains("ModelSnapshot.model"));
  }

  @Test
  void constructorAndWrappingDoNotReadOrCopyRows() {
    var poison =
        new AbstractMap<String, Object>() {
          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("copy");
          }

          public Object get(Object key) {
            throw new IllegalStateException("first-read");
          }
        };
    var wrapped = MapperMaterialEvidenceRecords.wrap(poison);
    assertTrue(calls.isEmpty());
    assertSame(poison, MapperMaterialEvidenceRecords.unwrap(wrapped));
    assertEquals(
        "first-read",
        assertThrows(IllegalStateException.class, () -> service.material(wrapped)).getMessage());
  }

  @Test
  void noMaterialStopsBeforeAnyCodecOrSql() {
    assertTrue(service.evaluationFile(lease).isEmpty());
    assertEquals(List.of("leaseTransaction", "fenced"), calls);
    service.checkMaterials(MapperMaterialEvidenceRecords.wrap(row));
    assertEquals(2, calls.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"material_digest", "material_snapshot", "goal", "material_system_digest"})
  void materialQualificationPreservesFirstFailure(String field) {
    seed("W1");
    row.put(field, field.equals("material_snapshot") ? null : "bad");
    mismatch(() -> service.material(MapperMaterialEvidenceRecords.wrap(row)));
    if (field.startsWith("material_s") || field.equals("material_digest"))
      assertFalse(calls.contains("successfulMaterialWrite"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"W1", "W4"})
  void bindFirstAndReplayRetainsReceiptAndIgnoresUpdateCount(String sample) {
    var m = seed(sample);
    String content = m.initialContent();
    var file =
        new ReportFile(
            m.target(),
            content == null ? 0 : 1,
            content == null ? null : Payloads.hash(content),
            content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "text/markdown");
    service.bindEvaluationFile(lease, file);
    assertEquals(file, service.initialFile(MapperMaterialEvidenceRecords.wrap(row)));
    assertTrue(calls.indexOf("existingMaterialStep") < calls.indexOf("bindMaterialReceipt"));
    calls.clear();
    service.bindEvaluationFile(lease, file);
    assertFalse(calls.contains("existingMaterialStep"));
    assertFalse(calls.contains("bindMaterialReceipt"));
    assertEquals(Optional.of(m.target()), service.evaluationFile(lease));
    writes = List.of(MapperMaterialEvidenceRecords.wrap(Map.of("id", "write")));
    assertTrue(service.evaluationFile(lease).isEmpty());
    row.put("material_file_digest", "bad");
    mismatch(() -> service.evaluationFile(lease));
  }

  @ParameterizedTest
  @ValueSource(strings = {"path", "version", "hash", "size", "media"})
  void receiptReplayIsExactAcrossAllFields(String field) {
    var m = seed("W4");
    String text = m.initialContent();
    var good =
        new ReportFile(
            m.target(),
            1,
            Payloads.hash(text),
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "text/markdown");
    service.bindEvaluationFile(lease, good);
    calls.clear();
    var bad =
        new ReportFile(
            field.equals("path") ? "wrong" : good.path(),
            field.equals("version") ? 0 : good.version(),
            field.equals("hash") ? "bad" : good.sha256(),
            field.equals("size") ? good.size() + 1 : good.size(),
            field.equals("media") ? "other" : good.mediaType());
    mismatch(() -> service.bindEvaluationFile(lease, bad));
    assertFalse(calls.contains("bindMaterialReceipt"));
  }

  @Test
  void firstBindingRejectsExistingStepBeforeSerialization() {
    var m = seed("W1");
    steps = List.of(MapperMaterialEvidenceRecords.wrap(Map.of()));
    mismatch(() -> service.bindEvaluationFile(lease, new ReportFile(m.target(), 0, null, 0, null)));
    assertFalse(calls.contains("json"));
    assertFalse(calls.contains("bindMaterialReceipt"));
  }

  @Test
  void writeHistorySkipsFollowingReadsButInitialReceiptAlwaysVerified() {
    var m = seed("W1");
    service.bindEvaluationFile(lease, new ReportFile(m.target(), 0, null, 0, null));
    history =
        List.of(
            MapperMaterialEvidenceRecords.wrap(Map.of("tool", "write")),
            MapperMaterialEvidenceRecords.wrap(
                Map.of("tool", "read", "output_snapshot", "corrupt")));
    service.checkMaterials(MapperMaterialEvidenceRecords.wrap(row));
    row.put("material_file_digest", "bad");
    mismatch(() -> service.checkMaterials(MapperMaterialEvidenceRecords.wrap(row)));
  }

  @Test
  void materialBodyReadsBusinessAndVersionBeforeEmptyReturnAndNeverAddsTransaction() {
    row.put("record_version", 1);
    service.materialBody(MapperMaterialEvidenceRecords.wrap(row), "not-json", 1);
    assertEquals(List.of("businessTask"), calls);
    row.put("record_version", "wrong");
    assertThrows(
        ClassCastException.class,
        () -> service.materialBody(MapperMaterialEvidenceRecords.wrap(row), null, 1));
    assertFalse(calls.contains("leaseTransaction"));
    assertFalse(calls.contains("readJson"));
  }

  @Test
  void invalidHistoryOrdinalRejectsBeforeResponseIteration() {
    var m = seed("W1");
    service.bindEvaluationFile(lease, new ReportFile(m.target(), 0, null, 0, null));
    String request =
        Payloads.json(
            Map.of(
                "requestBody",
                Payloads.json(
                    Map.of(
                        "tools",
                        m.tools(),
                        "messages",
                        List.of(
                            Map.of(
                                "role",
                                "system",
                                "content",
                                m.systemTemplate()
                                    .replace(
                                        "{{HOST_REPORT_TIME}}",
                                        JdbcLedger.time(row, "report_time").toString())),
                            Map.of("role", "user", "content", m.goal()))))));
    calls.clear();
    mismatch(() -> service.materialBody(MapperMaterialEvidenceRecords.wrap(row), request, 2));
    assertTrue(calls.contains("materialPrecedingSteps"));
    assertFalse(calls.contains("leaseTransaction"));
  }
}
