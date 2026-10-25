package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper.InstallationMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QuestionEligibilityPortTest {
  @Test
  void constructorAndRowAreLazy() {
    new MapperQuestionEligibility(null, null);
    var row =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            throw new AssertionError("no constructor reads");
          }
        };
    new MapperQuestionEligibility.Row(row);
  }

  @Test
  void pinMembersPreserveNullAndOpaqueValues() {
    var port = new MapperQuestionEligibility(null, null);
    assertThrows(NullPointerException.class, () -> port.version(null));
    assertThrows(NullPointerException.class, () -> port.digest(null));
    assertThrows(NullPointerException.class, () -> port.epoch(null));
    var pin = new PluginPin(null, null, Long.MIN_VALUE);
    assertNull(port.version(pin));
    assertNull(port.digest(pin));
    assertEquals(Long.MIN_VALUE, port.epoch(pin));
  }

  @Test
  void tenantCastOccursBeforeSession() {
    var reads = new ArrayList<String>();
    var task =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add(k.toString());
            return 17;
          }
        };
    assertThrows(
        ClassCastException.class, () -> new MapperQuestionEligibility(null, task).installation());
    assertEquals(List.of("tenant_id"), reads);
  }

  @Test
  void firstRowAndOriginalEmptyError() {
    var failure = assertThrows(TaskFailure.class, () -> MapperQuestionEligibility.first(List.of()));
    assertEquals(404, failure.status());
    assertEquals("NOT_FOUND", failure.code());
    var first = new HashMap<String, Object>();
    first.put("plugin_version", "first");
    assertSame(
        first,
        MapperQuestionEligibility.first(List.of(first, Map.of("plugin_version", "second")))
            .values());
  }

  @Test
  void rowMembersRemainLazyCastAndNumberLong() {
    var reads = new ArrayList<String>();
    var raw =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add(k.toString());
            return super.get(k);
          }
        };
    raw.put("enabled", false);
    raw.put("plugin_version", null);
    raw.put("plugin_digest", 17);
    raw.put("epoch", new BigDecimal("7.99"));
    var row = new MapperQuestionEligibility.Row(raw);
    assertNull(row.version());
    assertThrows(ClassCastException.class, row::digest);
    assertEquals(7, row.epoch());
    assertEquals(List.of("plugin_version", "plugin_digest", "epoch"), reads);
    raw.put("epoch", null);
    assertThrows(NullPointerException.class, row::epoch);
    raw.put("epoch", "7");
    assertThrows(ClassCastException.class, row::epoch);
  }

  @Test
  void taskFieldsRemainIndependentAndBindingIsNotNormalized() {
    var reads = new ArrayList<String>();
    var raw =
        new HashMap<String, Object>() {
          public Object get(Object k) {
            reads.add(k.toString());
            return super.get(k);
          }
        };
    raw.put("question_cleanup_state", 17);
    raw.put("question_binding", " raw ");
    var p = new MapperQuestionEligibility(null, raw);
    assertThrows(ClassCastException.class, p::cleanupState);
    assertEquals(" raw ", p.bindingSnapshot());
    assertNull(p.bindingDigest());
    assertEquals(
        List.of("question_cleanup_state", "question_binding", "question_binding_digest"), reads);
    assertThrows(NullPointerException.class, () -> p.hash(null));
  }

  @Test
  void originalContractUnknownNullAndBothBindings() {
    var contracts = new QuestionContracts();
    for (String version : new String[] {null, "unknown"})
      assertEquals(
          "PLUGIN_PIN_MISMATCH",
          assertThrows(TaskFailure.class, () -> contracts.contract(version)).code());
    assertEquals("blocking", contracts.mode("0.1.0"));
    assertEquals("timed", contracts.mode("0.2.0"));
    var p = new MapperQuestionEligibility(null, null);
    assertEquals(
        contracts.contract("0.2.0").descriptor().outputSha256(),
        p.contractOutputDigest(contracts.contract("0.2.0")));
    assertThrows(NullPointerException.class, () -> p.contractOutputDigest(null));
  }

  @Test
  void loadedMpExplicitStarHasOnlyTwoKeysAndNoLock() {
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var w = new QueryWrapper<InstallationEntity>();
    w.select("*").eq("tenant_id", "tenant").eq("plugin_id", "ask_user_question");
    var b =
        session
            .getConfiguration()
            .getMappedStatement(InstallationMapper.class.getName() + ".selectMaps")
            .getBoundSql(Map.of("ew", w));
    assertEquals(
        "SELECT * FROM installation WHERE (tenant_id = ? AND plugin_id = ?)",
        b.getSql().replaceAll("\\s+", " ").trim());
    assertEquals(
        List.of("tenant", "ask_user_question"),
        b.getParameterMappings().stream()
            .map(
                v ->
                    org.apache.ibatis.reflection.SystemMetaObject.forObject(Map.of("ew", w))
                        .getValue(v.getProperty()))
            .toList());
  }
}
