package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskAdmissionRecords;
import io.github.lovexiaopeng.jimu.kernel.task.service.impl.TaskAdmissionServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 保护重试/拒绝的阶段顺序，以及实际加载的SQL全键、参数和MP调用约束。 */
class TaskAdmissionServiceTest {
  private final Tasks.Identity owner = new Tasks.Identity("tenant-a", "alice");
  private final Tasks.Request request =
      new Tasks.Request(
          "goal", List.of("web_fetch"), List.of(), new Tasks.Budget(120, 8, 20, null));
  private final List<String> calls = new ArrayList<>();
  private TaskAdmissionRecords.Existing existing;
  private final Tasks.View view =
      new Tasks.View("id", "QUEUED", 1, Instant.EPOCH, Instant.EPOCH, null, null, null, List.of());

  private TaskAdmissionServiceImpl service() {
    var admission =
        (TaskAdmissionRecords.Admission)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskAdmissionRecords.Admission.class},
                (p, m, a) -> {
                  calls.add(m.getName());
                  return switch (m.getName()) {
                    case "room" -> new TaskAdmissionRecords.Room(100, 1, 0, 0, 0, 0, 0, 0);
                    case "existing" -> existing;
                    case "replay" -> view;
                    default ->
                        throw new AssertionError(
                            "Retry must not enter fresh admission: " + m.getName());
                  };
                });
    var records =
        (TaskAdmissionRecords)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TaskAdmissionRecords.class},
                (p, m, a) -> {
                  return switch (m.getName()) {
                    case "lock" -> {
                      calls.add("lock");
                      yield admission;
                    }
                    case "json" -> Payloads.json(a[0]);
                    case "hash" -> Payloads.hash((String) a[0]);
                    case "readRequest" -> Payloads.read((String) a[0], Tasks.Request.class);
                    case "questionsEnabled", "mcpEnabled" -> false;
                    default -> throw new AssertionError(m);
                  };
                });
    return new TaskAdmissionServiceImpl(
        records,
        new LedgerUnitOfWork() {
          public <T> T required(Supplier<T> operation) {
            calls.add("transaction");
            return operation.get();
          }
        });
  }

  @Test
  void retryPrecedesQueueAndModelQualification() {
    String snapshot = Payloads.json(request);
    existing = new TaskAdmissionRecords.Existing(snapshot, Payloads.hash(snapshot));
    assertEquals(view, service().submit(owner, "key", request, List.of(), null, null));
    assertEquals(List.of("transaction", "lock", "room", "existing", "replay"), calls);
  }

  @Test
  void corruptSnapshotRefusesBeforeReplay() {
    existing = new TaskAdmissionRecords.Existing(Payloads.json(request), "0".repeat(64));
    assertEquals(
        "SNAPSHOT_CORRUPT",
        assertThrows(
                TaskFailure.class,
                () -> service().submit(owner, "key", request, List.of(), null, null))
            .code());
    assertTrue(!calls.contains("replay"));
  }

  @Test
  void conflictingSnapshotRefusesBeforeReplay() {
    String snapshot =
        Payloads.json(
            new Tasks.Request("changed", request.capabilityIds(), List.of(), request.budget()));
    existing = new TaskAdmissionRecords.Existing(snapshot, Payloads.hash(snapshot));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                TaskFailure.class,
                () -> service().submit(owner, "key", request, List.of(), null, null))
            .code());
    assertTrue(!calls.contains("replay"));
  }

  @Test
  void capabilityCombinationFailsBeforeTransaction() {
    var bad = new Tasks.Request("goal", List.of("todo_write", "read"), List.of(), request.budget());
    assertEquals(
        "INVALID_CAPABILITIES",
        assertThrows(
                TaskFailure.class, () -> service().submit(owner, "key", bad, List.of(), null, null))
            .code());
    assertTrue(calls.isEmpty());
  }

  @Test
  void realMapperBindingsMatchRemovedSql() throws Exception {
    var config =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-task-removed-sql.json")),
            com.fasterxml.jackson.databind.JsonNode.class);
    assertEquals(26, inventory.path("removed").size());
    for (var site : inventory.path("removed")) {
      if (site.has("mp")) continue;
      var bound =
          config
              .getMappedStatement(
                  "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskAdmissionMapper."
                      + site.path("name").asText())
              .getBoundSql(Map.of());
      String expected = site.path("statement").asText().replaceAll("#\\{[^}]+}", "?");
      assertEquals(expected, bound.getSql().trim());
      assertTrue(!bound.getSql().contains("*") && !bound.getSql().contains("${"));
      assertEquals(site.path("parameters").size(), bound.getParameterMappings().size());
      for (int i = 0; i < bound.getParameterMappings().size(); i++)
        assertEquals(
            site.path("parameters").get(i).path("name").asText(),
            bound.getParameterMappings().get(i).getProperty());
    }
    assertTrue(
        !config.hasStatement(
            "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskLookupMapper.selectById"));
    var wrapper =
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity>();
    wrapper
        .select(
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity
                ::getTaskId,
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity
                ::getEvaluationRunId)
        .eq(
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity
                ::getTenantId,
            "t")
        .eq(
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity
                ::getOwnerActorId,
            "a")
        .eq(
            io.github.lovexiaopeng.jimu.adapters.persistence.task.entity.TaskLookupEntity
                ::getIdempotencyKey,
            "k");
    var select =
        config
            .getMappedStatement(
                "io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskLookupMapper.selectMaps")
            .getBoundSql(Map.of("ew", wrapper))
            .getSql()
            .replaceAll("\\s+", " ");
    assertTrue(select.startsWith("SELECT id,evaluation_run_id FROM task"), select);
    assertTrue(
        select.contains("tenant_id = ?")
            && select.contains("owner_actor_id = ?")
            && select.contains("idempotency_key = ?"),
        select);
  }
}
