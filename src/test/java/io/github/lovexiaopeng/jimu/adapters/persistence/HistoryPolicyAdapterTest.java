package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionHistoryStorageMapper;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineagePolicyV5;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class HistoryPolicyAdapterTest {
  @Test
  void sqlWriterUsesCompactSnapshotWithOriginalLogicalDigestOnlyForV5() {
    var captured = new AtomicReference<Object[]>();
    var mapper =
        (SessionHistoryStorageMapper)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionHistoryStorageMapper.class},
                (p, m, a) -> {
                  captured.set(a);
                  return 1;
                });
    var configuration = new Configuration();
    configuration.setEnvironment(
        new org.apache.ibatis.mapping.Environment(
            "codec-test",
            new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),
            new org.springframework.jdbc.datasource.DriverManagerDataSource()));
    var template =
        new SqlSessionTemplate(new DefaultSqlSessionFactory(configuration)) {
          @Override
          public <T> T getMapper(Class<T> type) {
            return type.cast(mapper);
          }
        };
    for (String capabilities :
        java.util.List.of(SessionLineagePolicyV5.CAPABILITIES, "session_event_read")) {
      var records = new JdbcSessionRecords(new JdbcTemplate(), null, template);
      var task =
          Map.<String, Object>of(
              "tenant_id", "tenant-a", "id", "task-a", "capabilities", capabilities);
      var adapter = new MapperSessionHistoryStorage(records, task);
      var binding = adapter.binding();
      assertEquals(records.contract(task).binding(), binding.snapshot());
      adapter.insert(binding);
      var args = captured.get();
      assertEquals(records.version(task), args[1]);
      assertEquals(records.contract(task).historySnapshot(null), args[2]);
      assertEquals(Payloads.hash(binding.snapshot()), args[3]);
      assertEquals("tenant-a", args[4]);
      assertEquals("task-a", args[5]);
      if (records.version(task) == 5) assertNotEquals(Payloads.hash((String) args[2]), args[3]);
      else assertEquals(binding.snapshot(), args[2]);
    }
  }

  @Test
  void normalAndEventHistoryReadersValidateEveryFreshSnapshotWithoutChangingGenericEquals() {
    var q =
        new SessionQueryContext(System.nanoTime() + 2_000_000_000L, new QueryResources(0, 0, 0));
    q.restrictLineage();
    var records = new JdbcSessionRecords(new JdbcTemplate(), q);
    var task = Map.<String, Object>of("capabilities", SessionLineagePolicyV5.CAPABILITIES);
    String wire = records.v5.historySnapshot(null);
    for (boolean accepted : java.util.List.of(false, true)) {
      var reader = new MapperSessionHistoryProofs(records, task, null, accepted);
      assertTrue(reader.matchesSnapshot(wire));
      assertTrue(reader.matchesSnapshot(records.v5.binding()));
      assertFalse(reader.matchesSnapshot(wire.replace("catalog_digest", "wrong_catalog")));
      assertFalse(reader.matches(wire, records.v5.binding()));
      assertSame(records.v5.binding(), reader.binding());
      assertEquals(Payloads.hash(records.v5.binding()), reader.hash(reader.binding()));
    }
    assertEquals(65536, q.scratchLive());
  }
}
