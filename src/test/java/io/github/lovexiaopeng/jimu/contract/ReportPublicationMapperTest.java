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

/** 13 个原 JDBC 点逐条核实际加载 SQL、全列投影和全部45个绑定位置。 */
class ReportPublicationMapperTest {
  @Test
  void everyReportSiteRetainsSqlAndBindingOrder() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-report-removed-sql.json")),
            JsonNode.class);
    var config =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertEquals(13, inventory.path("removed").size());
    int binds = 0;
    for (var site : inventory.path("removed")) {
      String id =
          "io.github.lovexiaopeng.jimu.adapters.persistence."
              + site.path("module").asText()
              + ".mapper."
              + site.path("mapper").asText()
              + "."
              + site.path("method").asText();
      var bound = config.getMappedStatement(id).getBoundSql(Map.of());
      String expected = site.path("originalSql").asText();
      if (expected.contains("SELECT *")) {
        String table = expected.substring(expected.indexOf(" FROM ") + 6).split(" ")[0];
        var columns = new ArrayList<String>();
        inventory.path("columns").path(table).forEach(c -> columns.add(c.asText()));
        expected = expected.replace("SELECT *", "SELECT " + String.join(",", columns));
      }
      assertEquals(expected, bound.getSql().trim(), id);
      assertEquals(site.path("parameters").size(), bound.getParameterMappings().size(), id);
      for (int index = 0; index < bound.getParameterMappings().size(); index++)
        assertEquals(
            site.path("parameters").get(index).path("name").asText(),
            bound.getParameterMappings().get(index).getProperty(),
            id);
      binds += bound.getParameterMappings().size();
    }
    assertEquals(45, binds);
  }
}
