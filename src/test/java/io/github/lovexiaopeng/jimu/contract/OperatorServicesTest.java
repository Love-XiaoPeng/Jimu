package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.AccountEvidence;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Requirement;
import io.github.lovexiaopeng.jimu.kernel.operator.api.OperatorScopes.Scope;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingCodec;
import io.github.lovexiaopeng.jimu.kernel.operator.port.ScopeBindingRecords;
import io.github.lovexiaopeng.jimu.kernel.operator.service.ExecutionScopeService.Input;
import io.github.lovexiaopeng.jimu.kernel.operator.service.impl.ExecutionScopeServiceImpl;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 冻结编码端口替身仅隔离Service规则；结构/实际账本由私有MySQL差异测试验证。 */
class OperatorServicesTest {
  final List<String> calls = new ArrayList<>();
  final Requirement req = new Requirement(1, "issue", "uuid", "schema", "tenant", "run", "digest");
  final Scope scope =
      new Scope(
          1, "scope", "issue", "uuid", "schema", "tenant", "run", "digest", "digest", "digest",
          "digest", "digest", "model", "model", Map.of(), Map.of(), true);
  final Input input =
      new Input(
          scope,
          Map.of(),
          new AccountEvidence("local", "synthetic", "localhost", true, true, "digest"),
          "first");
  final Records records = new Records();
  final LedgerUnitOfWork uow =
      new LedgerUnitOfWork() {
        public <T> T required(Supplier<T> work) {
          calls.add("begin");
          var original = records.bound;
          try {
            T result = work.get();
            calls.add("commit");
            return result;
          } catch (RuntimeException failure) {
            records.bound = original;
            calls.add("rollback");
            throw failure;
          }
        }
      };
  final ScopeBindingCodec codec =
      new ScopeBindingCodec() {
        public String canonical(Scope s) {
          return "snapshot";
        }

        public String json(Object value) {
          return "json";
        }

        public String hash(String value) {
          return "digest";
        }

        public boolean digest(String value) {
          return "digest".equals(value);
        }

        public String approvedCandidate(String approval) {
          return "qwen3.7-plus-2026-05-26";
        }

        public Plan plan() {
          return new Plan("plan", "digest", "digest", "digest");
        }

        public void validateProfile(ModelSnapshot model, boolean localOnly) {
          throw new AssertionError();
        }

        public String profileDigest(ModelSnapshot model) {
          throw new AssertionError();
        }

        public boolean localEvaluation(ModelSnapshot model) {
          throw new AssertionError();
        }
      };

  ExecutionScopeServiceImpl service() {
    return new ExecutionScopeServiceImpl(records, codec, uow, req);
  }

  void rejected(String code) {
    assertEquals(code, assertThrows(TaskFailure.class, () -> service().bindOnce(input)).code());
    assertFalse(calls.contains("insert"));
    assertEquals("rollback", calls.getLast());
  }

  @Test
  void firstBindingLocksRunThenBudgetAndWritesOnlyAfterProof() {
    var result = service().bindOnce(input);
    assertTrue(calls.indexOf("run") < calls.indexOf("budget"));
    assertTrue(calls.indexOf("reservations") < calls.indexOf("insert"));
    assertEquals("first", result.get("bound_by"));
    assertEquals("commit", calls.getLast());
  }

  @Test
  void retryRetainsFirstBinderAndNeverLocksBudget() {
    records.bound = records.row();
    var result = service().bindOnce(new Input(scope, Map.of(), input.accountEvidence(), "second"));
    assertEquals("first", result.get("bound_by"));
    assertFalse(calls.contains("budget"));
    assertFalse(calls.contains("insert"));
  }

  @Test
  void currentQualificationPrecedesIdempotency() {
    records.bound = records.row();
    records.receiptCount = 0;
    rejected("EVALUATION_SCOPE_UNAVAILABLE");
    assertFalse(calls.contains("binding"));
  }

  @Test
  void stoppedRunRefusesBeforeExistingBinding() {
    records.bound = records.row();
    records.open = false;
    rejected("MODEL_BUDGET_EXCEEDED");
    assertFalse(calls.contains("binding"));
  }

  @Test
  void ambiguousExternalReceiptRefusesWithoutWrites() {
    records.receiptCount = 2;
    rejected("EVALUATION_SCOPE_UNAVAILABLE");
  }

  @Test
  void differingCanonicalBindingConflicts() {
    records.bound = new HashMap<>(Map.of("scope_snapshot", "different", "scope_digest", "digest"));
    rejected("EVALUATION_SCOPE_BINDING_CONFLICT");
  }

  @Test
  void unprovenReservationNeverCreatesAuthority() {
    records.badReservations = 1;
    rejected("EVALUATION_SCOPE_LEDGER_UNPROVEN");
  }

  @Test
  void dispatchCounterMismatchPrecedesLedgerRead() {
    records.dispatches = 1;
    rejected("EVALUATION_SCOPE_NOT_ALLOWED");
    assertFalse(calls.contains("reservations"));
  }

  @Test
  void readbackFailureRollsBackInsertedScope() {
    records.failReadback = true;
    assertThrows(IllegalStateException.class, () -> service().bindOnce(input));
    assertTrue(calls.contains("insert"));
    assertEquals(null, records.bound);
    assertEquals("rollback", calls.getLast());
  }

  @Test
  void invalidActorRefusesBeforeStartingTransaction() {
    assertThrows(
        TaskFailure.class,
        () -> service().bindOnce(new Input(scope, Map.of(), input.accountEvidence(), " ")));
    assertTrue(calls.isEmpty());
  }

  class Records implements ScopeBindingRecords {
    Map<String, Object> bound;
    boolean open = true, failReadback;
    int receiptCount = 1;
    long badReservations, dispatches;

    Map<String, Object> row() {
      return new HashMap<>(
          Map.of("scope_snapshot", "snapshot", "scope_digest", "digest", "bound_by", "first"));
    }

    public Run lockRun(String tenant, String run) {
      assertEquals("tenant", tenant);
      assertEquals("run", run);
      calls.add("run");
      return new Run(run, "plan", "digest", open ? "OPEN" : "STOPPED", 63, 500000000, 0, 0);
    }

    public List<Receipt> receipts(String tenant) {
      return java.util.Collections.nCopies(
          receiptCount,
          new Receipt(1, "issue", "uuid", "run", "approval", "digest", "digest", "digest"));
    }

    public String serverUuid() {
      return "uuid";
    }

    public String schema() {
      return "schema";
    }

    public Binding binding(String tenant, String run) {
      calls.add("binding");
      return bound == null
          ? null
          : new Binding(
              (String) bound.get("scope_snapshot"), (String) bound.get("scope_digest"), bound);
    }

    public Budget lockBudget(String tenant) {
      calls.add("budget");
      return new Budget(0, 0);
    }

    public long dispatchCount(String tenant, String run) {
      return dispatches;
    }

    public long accountedCost(String tenant, String run) {
      return 0;
    }

    public long invalidTasks(String tenant, String run) {
      return 0;
    }

    public long tenantCost(String tenant) {
      return 0;
    }

    public long tenantModels(String tenant) {
      return 0;
    }

    public long invalidReservations(String tenant, String run) {
      calls.add("reservations");
      return badReservations;
    }

    public void insert(String tenant, String run, String snapshot, String digest, String actor) {
      calls.add("insert");
      bound = row();
    }

    public Map<String, Object> ledger(String tenant, String run) {
      if (failReadback) throw new IllegalStateException("readback");
      return Map.of();
    }
  }
}
