package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.util.Map;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SessionTaskFinalSourceTest {
  private static org.apache.ibatis.mapping.SqlSource parse(String sql) {
    var script = new XPathParser("<select><![CDATA[" + sql + "]]></select>").evalNode("/select");
    return new SessionHistoryMetadataLanguageDriver()
        .createSqlSource(new Configuration(), script, Map.class);
  }

  private static String taskSql() {
    return SessionHistoryMetadataLanguageDriver.TASK_SQL.replace(
        "tenant_id=? AND id=?", "tenant_id=#{tenant} AND id=#{taskId}");
  }

  @Test
  void taskPinRequiresExactLengthProjectionAndParameterOrder() {
    var source = parse(taskSql());
    assertTrue(SessionHistoryMetadataLanguageDriver.ownsTask(source));
    var bound = source.getBoundSql(Map.of("tenant", "a", "taskId", "b"));
    assertEquals(SessionHistoryMetadataLanguageDriver.TASK_SQL, bound.getSql());
    assertEquals("tenant", bound.getParameterMappings().get(0).getProperty());
    assertEquals("taskId", bound.getParameterMappings().get(1).getProperty());
    for (String changed :
        new String[] {
          taskSql().replace("CONVERT(capabilities USING utf8mb4)", "capabilities"),
          taskSql().replace("utf8mb4", "latin1"),
          taskSql().replace("OCTET_LENGTH(CONVERT(capabilities USING utf8mb4))", "1"),
          taskSql().replace("#{tenant}", "#{taskId}"),
          taskSql().replace("#{taskId}", "#{tenant}")
        }) assertFalse(SessionHistoryMetadataLanguageDriver.ownsTask(parse(changed)));
    // The new TASK pin must never qualify the independent history reader.
    assertFalse(SessionHistoryMetadataLanguageDriver.owns(source));
    var history =
        parse(
            SessionHistorySizedMetadataBatch.SQL.replace(
                "tenant_id=? AND task_id=?", "tenant_id=#{tenant} AND task_id=#{taskId}"));
    assertTrue(SessionHistoryMetadataLanguageDriver.owns(history));
    assertFalse(SessionHistoryMetadataLanguageDriver.ownsTask(history));
  }

  @Test
  void parsingInsideWorkPaysAndDoesNotCreateAnUnchargedPin() {
    var fixture = new SessionQueryTransactionTest.Fixture();
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    fixture.transaction.required(
        q,
        () -> {
          long before = q.resources().operations();
          assertFalse(SessionHistoryMetadataLanguageDriver.ownsTask(parse(taskSql())));
          assertTrue(q.resources().operations() > before);
          return null;
        });
    assertEquals(65536, q.scratchLive());
  }
}
