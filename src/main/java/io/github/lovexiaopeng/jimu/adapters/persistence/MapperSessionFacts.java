package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryAttemptEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.ScopeQueryStepEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.entity.SessionFactTerminalEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionFactAttemptMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionFactStepMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.session.mapper.SessionFactTerminalMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionFacts;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.util.LinkedCaseInsensitiveMap;

/** Same DS/UoW and explicit query, without an additional search meter or eager value conversion. */
final class MapperSessionFacts implements SessionFacts.Fact {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperSessionFacts(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  static SessionFacts.Append append(
      JdbcLedger ledger,
      Map<String, Object> task,
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String digest,
      Lease lease) {
    return new SessionFacts.Append() {
      public void changed(String changed) {
        ledger.event(task, changed, "session_history.changed", null);
      }

      public long reliable(String changed) {
        return ledger
            .mappers
            .events()
            .selectFactSequenceValue(
                ledger.queryContext(), s(task, "tenant_id"), s(task, "id"), changed);
      }

      public long append(long reliable) {
        return ledger.sessions.append(
            task, fact, type, source, data, redacted, digest, lease, reliable);
      }

      public boolean terminal() {
        return type.equals("turn.ended");
      }

      public void account(boolean closing) {
        ledger.sessions.account(task, closing);
      }
    };
  }

  private Map<String, Object> one(Class<?> mapper, Object wrapper) {
    var args = new java.util.HashMap<String, Object>();
    args.put("query", ledger.queryContext());
    args.put("ew", wrapper);
    List<Map<String, Object>> rows =
        ledger.mappers.session().selectList(mapper.getName() + ".selectMaps", args);
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  public boolean session() {
    return JdbcSessionRecords.task(task);
  }

  public SessionFacts.Text goalText() {
    var text = ledger.sessions.contracts.publicText(s(task, "goal"), ledger.sessions.sensitive());
    return new SessionFacts.Text(text.content(), text.redacted());
  }

  public String requestDigest() {
    return s(task, "request_digest");
  }

  public SessionFacts.Step step(String id) {
    var row =
        one(
            SessionFactStepMapper.class,
            new QueryWrapper<ScopeQueryStepEntity>()
                .select("*")
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("id", id)
                .eq("task_id", s(task, "id")));
    return new SessionFacts.Step() {
      public String state() {
        return s(row, "state");
      }

      public long ordinal() {
        return n(row, "ordinal");
      }

      public String requestDigest() {
        return s(row, "request_digest");
      }

      public String responseDigest() {
        return s(row, "response_digest");
      }

      public SessionFacts.Response response() {
        var response = Payloads.read(s(row, "response_snapshot"), DurableAgent.Response.class);
        return new SessionFacts.Response() {
          public SessionFacts.Text text() {
            var text =
                ledger.sessions.contracts.publicText(
                    response.content(), ledger.sessions.sensitive());
            return new SessionFacts.Text(text.content(), text.redacted());
          }

          public SessionFacts.Projection project() {
            var projected = ledger.sessions.projectedCalls(task, response.calls());
            return new SessionFacts.Projection() {
              public boolean redacted() {
                return projected.redacted();
              }

              public List<?> calls() {
                return projected.calls();
              }
            };
          }
        };
      }
    };
  }

  public SessionFacts.Attempt attempt() {
    var row =
        one(
            SessionFactAttemptMapper.class,
            new QueryWrapper<ScopeQueryAttemptEntity>()
                .select("*")
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("id", s(task, "current_attempt_id"))
                .eq("task_id", s(task, "id")));
    return new SessionFacts.Attempt() {
      public String id() {
        return s(row, "id");
      }

      public long fence() {
        return n(row, "fence");
      }
    };
  }

  public String tenant() {
    return s(task, "tenant_id");
  }

  public String owner() {
    return s(task, "owner_actor_id");
  }

  public String taskId() {
    return s(task, "id");
  }

  public String worker() {
    return s(task, "worker_id");
  }

  public Instant deadline() {
    return JdbcLedger.time(task, "deadline");
  }

  public String goal() {
    return s(task, "goal");
  }

  public List<String> caps() {
    return ledger.caps(task);
  }

  public long installationEpoch() {
    return n(task, "installation_epoch");
  }

  public SessionFacts.Terminal terminal() {
    var row =
        one(
            SessionFactTerminalMapper.class,
            new QueryWrapper<SessionFactTerminalEntity>()
                .select("event_id", "type", "status", "code")
                .eq("tenant_id", s(task, "tenant_id"))
                .eq("task_id", s(task, "id"))
                .eq("fact_id", "terminal"));
    // MP selectMaps owns raw values; restore exactly JdbcTemplate's four-column map and order.
    var terminal = new LinkedCaseInsensitiveMap<Object>();
    for (String column : List.of("event_id", "type", "status", "code"))
      terminal.put(column, row.get(column));
    return new SessionFacts.Terminal() {
      public Object state() {
        return terminal.get("status");
      }

      public Object code() {
        return terminal.get("code");
      }

      public String digest() {
        return Payloads.hash(Payloads.json(terminal));
      }
    };
  }

  public Map<String, Object> source(String kind, String step, String invocation) {
    return ledger.sessions.source(kind, step, invocation);
  }

  public void append(
      String fact,
      String type,
      Map<String, Object> source,
      Map<String, Object> data,
      boolean redacted,
      String digest,
      Lease lease) {
    ledger.sessionAppend(task, fact, type, source, data, redacted, digest, lease);
  }
}
