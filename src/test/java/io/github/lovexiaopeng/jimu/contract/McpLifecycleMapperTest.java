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

/** 18 个原 JDBC 点逐条核实际加载 SQL、全列投影和全部65个绑定位置。 */
class McpLifecycleMapperTest {
  @Test
  void everyMcpLifecycleSiteRetainsSqlAndBindingOrder() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-mcp-lifecycle-removed-sql.json")),
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
    assertEquals(18, inventory.path("operations").size());
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
      assertEquals(expected, bound.getSql().trim(), id);
      assertEquals(site.path("args").size(), bound.getParameterMappings().size(), id);
      for (int index = 0; index < bound.getParameterMappings().size(); index++)
        assertEquals(
            site.path("args").get(index).path("name").asText(),
            bound.getParameterMappings().get(index).getProperty(),
            id);
      binds += bound.getParameterMappings().size();
    }
    assertEquals(65, binds);
  }
}
