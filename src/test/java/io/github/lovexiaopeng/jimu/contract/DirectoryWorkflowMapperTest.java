package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 21 个目录 JDBC 点逐条核实际加载 SQL、冻结投影和81个绑定位置。 */
class DirectoryWorkflowMapperTest {
  @Test
  void everyDirectoryWorkflowSiteRetainsSqlAndBindingOrder() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of(
                    "docs/verification/XIAO-60/evidence/b2-directory-workflow-removed-sql.json")),
            JsonNode.class);
    var frozenColumns =
        Payloads.read(
                Files.readString(Path.of("docs/verification/XIAO-60/evidence/b2-removed-sql.json")),
                JsonNode.class)
            .path("columns");
    var config =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertEquals(21, inventory.path("operations").size());
    int binds = 0;
    for (var site : inventory.path("operations")) {
      String id =
          "io.github.lovexiaopeng.jimu.adapters.persistence."
              + site.path("domain").asText()
              + ".mapper."
              + site.path("mapper").asText()
              + "."
              + site.path("name").asText();
      var bound = config.getMappedStatement(id).getBoundSql(Map.of());
      String expected = site.path("original").asText();
      if (expected.contains("SELECT *")) {
        String table = expected.substring(expected.indexOf(" FROM ") + 6).split(" ")[0];
        assertEquals(frozenColumns.path(table), inventory.path("columns").path(table), table);
        var columns = new ArrayList<String>();
        frozenColumns.path(table).forEach(c -> columns.add(c.asText()));
        expected = expected.replace("SELECT *", "SELECT " + String.join(",", columns));
      }
      if (expected.contains("SELECT c.*,")) {
        var cols = new ArrayList<String>();
        frozenColumns.path("mcp_cursor").forEach(c -> cols.add("c." + c.asText()));
        expected = expected.replace("SELECT c.*,", "SELECT " + String.join(",", cols) + ",");
      }
      if (expected.contains("SELECT n.*")) {
        var cols = new ArrayList<String>();
        frozenColumns.path("native_search_run").forEach(c -> cols.add("n." + c.asText()));
        expected = expected.replace("SELECT n.*", "SELECT " + String.join(",", cols));
      }
      for (var alias : Map.of("i", "invocation", "p", "task_directory").entrySet()) {
        var cols = new ArrayList<String>();
        frozenColumns
            .path(alias.getValue())
            .forEach(c -> cols.add(alias.getKey() + "." + c.asText()));
        expected = expected.replace(alias.getKey() + ".*", String.join(",", cols));
      }
      assertEquals(
          expected.replaceAll("\\s+", " ").trim(),
          bound.getSql().replaceAll("\\s+", " ").trim(),
          id);
      assertEquals(site.path("args").size(), bound.getParameterMappings().size(), id);
      for (int index = 0; index < bound.getParameterMappings().size(); index++)
        assertEquals(
            site.path("args").get(index).path("name").asText(),
            bound.getParameterMappings().get(index).getProperty(),
            id);
      binds += bound.getParameterMappings().size();
    }
    assertEquals(81, binds);
  }
}
