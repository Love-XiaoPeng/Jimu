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
