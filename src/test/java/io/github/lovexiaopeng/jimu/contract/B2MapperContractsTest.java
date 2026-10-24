package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 将已删除的 JDBC 语句逐条与实际加载的 XML/绑定顺序对照，列展开另有真实 schema22 断言。 */
class B2MapperContractsTest {
  @Test
  void modelCutoverLoadsFixedPredicatesAndOrdinaryCompositeInsert() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-model-removed-sql.json")),
            JsonNode.class);
    var columns =
        Payloads.read(
                Files.readString(Path.of("docs/verification/XIAO-60/evidence/b2-removed-sql.json")),
                JsonNode.class)
            .path("columns");
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var configuration = session.getConfiguration();
    assertEquals(55, inventory.path("removed").size());
    for (var site : inventory.path("removed")) {
      String namespace =
          "io.github.lovexiaopeng.jimu.adapters.persistence."
              + site.path("module").asText()
              + ".mapper."
              + site.path("mapper").asText();
      String id = namespace + "." + site.path("method").asText();
      assertTrue(configuration.hasStatement(id), id);
      if (site.has("replacement")) {
        assertTrue(
            !configuration.hasStatement(namespace + ".selectById"),
            "Composite table must not inject by-id: " + namespace);
        var parameters = new java.util.HashMap<String, Object>();
        var entity =
            site.path("module").asText().equals("model")
                ? (Object)
                    new io.github.lovexiaopeng.jimu.adapters.persistence.model.entity
                        .ModelDispatchEntity()
                : new io.github.lovexiaopeng.jimu.adapters.persistence.invocation.entity
                    .InvocationEntity();
        var meta = org.apache.ibatis.reflection.SystemMetaObject.forObject(entity);
        for (var property : meta.getGetterNames())
          parameters.put(
              property,
              property.equals("ordinal")
                  ? 1
                  : property.equals("installationEpoch") || property.equals("reservedCost")
                      ? 1L
                      : "synthetic");
        parameters.put(
            "query",
            new io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext(
                Long.MAX_VALUE, null));
        var bound = configuration.getMappedStatement(id).getBoundSql(parameters);
        assertTrue(bound.getSql().contains("tenant_id"));
        assertTrue(bound.getSql().contains("task_id"));
        if (site.path("module").asText().equals("model")) {
          parameters.put("priceVersion", null);
          parameters.put("currency", null);
          parameters.put("reservedCost", null);
          var free = configuration.getMappedStatement(id).getBoundSql(parameters);
          assertTrue(
              free.getSql().contains("price_version") && free.getSql().contains("reserved_cost"),
              "Explicit free-model nulls retained");
        }
        continue;
      }
      var bound = configuration.getMappedStatement(id).getBoundSql(Map.of());
      String expected = site.path("originalSql").asText();
      if (expected.contains("SELECT *")) {
        String table = expected.substring(expected.indexOf(" FROM ") + 6).split(" ")[0];
        var projection = new java.util.ArrayList<String>();
        columns.path(table).forEach(c -> projection.add(c.asText()));
        expected = expected.replace("SELECT *", "SELECT " + String.join(",", projection));
      }
      assertEquals(expected, bound.getSql().trim(), id);
      assertEquals(site.path("parameters").size(), bound.getParameterMappings().size(), id);
      for (int i = 0; i < bound.getParameterMappings().size(); i++)
        assertEquals(
            site.path("parameters").get(i).path("name").asText(),
            bound.getParameterMappings().get(i).getProperty(),
            id);
    }
  }

  @Test
  void loadedStatementsPreservePredicatesAndParameterOrder() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(Path.of("docs/verification/XIAO-60/evidence/b2-removed-sql.json")),
            JsonNode.class);
    var session =
        LedgerMapperSessions.create(
            new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"));
    var configuration = session.getConfiguration();
    for (var site : inventory.path("removed")) {
      String id =
          "io.github.lovexiaopeng.jimu.adapters.persistence."
              + site.path("module").asText()
              + ".mapper."
              + site.path("mapper").asText()
              + "."
              + site.path("method").asText();
      assertTrue(configuration.hasStatement(id), id);
      var bound = configuration.getMappedStatement(id).getBoundSql(Map.of());
      String expected = site.path("originalSql").asText();
      if (expected.contains("SELECT *")) {
        String table = expected.substring(expected.indexOf(" FROM ") + 6).split(" ")[0];
        var columns = new java.util.ArrayList<String>();
        inventory.path("columns").path(table).forEach(column -> columns.add(column.asText()));
        expected = expected.replace("SELECT *", "SELECT " + String.join(",", columns));
      }
      if (expected.contains("SELECT w.*")) {
        var columns = new java.util.ArrayList<String>();
        inventory
            .path("columns")
            .path("worker_process")
            .forEach(column -> columns.add("w." + column.asText()));
        expected = expected.replace("SELECT w.*", "SELECT " + String.join(",", columns));
      }
      assertEquals(expected, bound.getSql().trim(), id);
      assertEquals(site.path("parameters").size(), bound.getParameterMappings().size(), id);
      for (int i = 0; i < bound.getParameterMappings().size(); i++)
        assertEquals(
            site.path("parameters").get(i).path("name").asText(),
            bound.getParameterMappings().get(i).getProperty(),
            id);
    }
  }
}
