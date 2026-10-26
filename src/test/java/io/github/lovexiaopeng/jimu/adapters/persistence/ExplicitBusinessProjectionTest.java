package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实加载Mapper，核schema22完整原列序、绑定和锁；新业务星号必须失败。 */
class ExplicitBusinessProjectionTest {
  private static final Pattern STAR = Pattern.compile("(?is)\\bSELECT\\s+(?:\\w+\\.)?\\*");

  static void requireExplicit(String sql, String id) {
    if (id.equals(
        "io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.ExecutionScopeMapper.consumptionReceipts")) {
      assertEquals("SELECT * FROM jimu_provisioning_receipt WHERE tenant_id=#{tenant}", sql);
    } else assertFalse(STAR.matcher(sql).find(), id);
  }

  private JsonNode inventory(String name) throws Exception {
    return Payloads.read(
        Files.readString(Path.of("docs/verification/XIAO-60/evidence/" + name)), JsonNode.class);
  }

  @Test
  void everyChangedXmlRetainsAllOriginalColumnsPredicatesAndBindingOrder() throws Exception {
    var columns = inventory("review-fix-schema22-columns.json");
    var originalColumns = inventory("b2-removed-sql.json").path("columns");
    for (var fields = originalColumns.fields(); fields.hasNext(); ) {
      var entry = fields.next();
      assertEquals(entry.getValue(), columns.path(entry.getKey()), entry.getKey());
    }
    var configuration =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    var operations = inventory("review-fix-projections.json").path("operations");
    assertEquals(17, operations.size());
    for (var operation : operations) {
      String original = operation.path("original").asText();
      var names = new ArrayList<String>();
      String alias = original.startsWith("SELECT q.*") ? "q." : "";
      columns.path(operation.path("table").asText()).forEach(c -> names.add(alias + c.asText()));
      String expected =
          original.replace("SELECT " + alias + "*", "SELECT " + String.join(",", names));
      var bound =
          configuration.getMappedStatement(operation.path("id").asText()).getBoundSql(Map.of());
      assertEquals(
          expected.replaceAll("#\\{\\w+}", "?"),
          bound.getSql().trim(),
          operation.path("id").asText());
      var params =
          Pattern.compile("#\\{(\\w+)}").matcher(original).results().map(r -> r.group(1)).toList();
      assertEquals(
          params, bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }
  }

  @Test
  void wrapperColumnsAreFullOrderedSchemaAndNoNewBusinessStarsAreAllowed() throws Exception {
    var columns = inventory("review-fix-schema22-columns.json");
    var actual =
        Map.of(
            "step",
            Schema22Columns.STEP,
            "task_attempt",
            Schema22Columns.TASK_ATTEMPT,
            "invocation",
            Schema22Columns.INVOCATION,
            "installation",
            Schema22Columns.INSTALLATION,
            "step_session_scope",
            Schema22Columns.STEP_SESSION_SCOPE,
            "task_todo_projection",
            Schema22Columns.TASK_TODO_PROJECTION);
    for (var item : actual.entrySet()) {
      var expected = new ArrayList<String>();
      columns.path(item.getKey()).forEach(c -> expected.add(c.asText()));
      assertEquals(expected, List.of(item.getValue().split(",")), item.getKey());
    }
    try (var files = Files.walk(Path.of("src/main/resources/mapper"))) {
      for (var file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        String xml = Files.readString(file);
        String namespace =
            Pattern.compile("<mapper namespace=\"([^\"]+)\"")
                .matcher(xml)
                .results()
                .findFirst()
                .orElseThrow()
                .group(1);
        for (var statement :
            Pattern.compile("(?s)<select\\s+id=\"([^\"]+)\"[^>]*>(.*?)</select>")
                .matcher(xml)
                .results()
                .toList()) {
          requireExplicit(
              statement.group(2).replace("<![CDATA[", "").replace("]]>", "").trim(),
              namespace + "." + statement.group(1));
        }
      }
    }
    try (var files =
        Files.walk(Path.of("src/main/java/io/github/lovexiaopeng/jimu/adapters/persistence"))) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList())
        assertFalse(
            Pattern.compile("\\.select\\(\\s*\"\\*\"").matcher(Files.readString(file)).find(),
            file.toString());
    }
  }

  @Test
  void negativeGateRejectsNewQualifiedAndUnqualifiedBusinessStar() {
    for (String sql :
        List.of("SELECT * FROM task", "SELECT q.* FROM user_question q", "SELECT\n * FROM step"))
      assertThrows(AssertionError.class, () -> requireExplicit(sql, "new.mapper.businessRead"));
    assertThrows(
        AssertionError.class,
        () ->
            requireExplicit(
                "SELECT * FROM task",
                "io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper.ExecutionScopeMapper.consumptionReceipts"));
  }
}
