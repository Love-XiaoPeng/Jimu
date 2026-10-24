package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.PriorSessionMatcher;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution;
import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import io.github.lovexiaopeng.jimu.plugin.api.SessionSearchPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同DS/UoW的有界扫描/MP窗口；原codec/matcher/JSON节点及序列化留在适配器。 */
final class MapperSessionScopeExecution {
  private final JdbcSessionRecords records;
  private final Map<String, Object> caller;
  private final Runnable deadline;

  MapperSessionScopeExecution(
      JdbcSessionRecords records, Map<String, Object> caller, Runnable deadline) {
    this.records = records;
    this.caller = caller;
    this.deadline = deadline;
  }

  private record Value(Object raw) implements SessionScopeExecution.Value {}

  private record Ref(Map<String, Object> raw) implements SessionScopeExecution.Ref {}

  private record Cut(JsonNode raw) implements SessionScopeExecution.Cut {
    public long upper() {
      return raw.path("upper_seq").asLong();
    }
  }

  private final class Event implements SessionScopeExecution.Event {
    private final Map<String, Object> source, row;

    Event(Map<String, Object> source, Map<String, Object> row) {
      this.source = source;
      this.row = row;
    }

    public long seq() {
      return n(row, "seq");
    }

    public String digest() {
      return s(row, "event_digest");
    }

    public long storedBytes() {
      return n(row, "event_bytes");
    }

    public int snapshotBytes() {
      return SessionContracts.bytes(s(row, "event_snapshot"));
    }

    public SessionScopeExecution.Value verify() {
      return new Value(records.verify(source, row));
    }
  }

  private final class Source implements SessionScopeExecution.Source {
    private final Map<String, Object> task;

    Source(Map<String, Object> task) {
      this.task = task;
    }

    public List<SessionScopeExecution.Event> scan(long upper) {
      return records.priorScan(s(caller, "tenant_id"), s(task, "id"), upper).stream()
          .<SessionScopeExecution.Event>map(r -> new Event(task, r))
          .toList();
    }

    public SessionScopeExecution.Ref ref(long seq, String digest) {
      return scopeRef(s(task, "id"), seq, digest);
    }

    public SessionScopeExecution.Window window(
        SessionEventPort.Input input, long upper, String boundary) {
      return MapperSessionScopeExecution.this.window(task, input, upper, boundary);
    }
  }

  private final class Scope implements SessionScopeExecution.Scope {
    private final JdbcSessionScopes.Scope scope;

    Scope(JdbcSessionScopes.Scope scope) {
      this.scope = scope;
    }

    public boolean contains(String id) {
      return scope.sources().stream().anyMatch(source -> id.equals(s(source, "id")));
    }

    public int size() {
      return scope.sources().size();
    }

    public SessionScopeExecution.Source source(int index) {
      return new Source(scope.sources().get(index));
    }

    public SessionScopeExecution.Cut cut(int index) {
      return new Cut(scope.authority().path("targets").get(index));
    }

    public SessionScopeExecution.Source find(String id) {
      return new Source(
          scope.sources().stream()
              .filter(source -> id.equals(s(source, "id")))
              .findFirst()
              .orElseThrow(JdbcSessionScopes::unavailable));
    }

    public String digest() {
      return s(scope.row(), "authority_digest");
    }
  }

  SessionScopeExecution.Prior prior(SessionSearchPort.Input input) {
    return new SessionScopeExecution.Prior() {
      public SessionScopeExecution.Scope require() {
        return new Scope(records.scopes.require(caller));
      }

      public List<String> sessions() {
        return input.sessionIds();
      }

      public SessionScopeExecution.Matcher matcher() {
        var matcher = new PriorSessionMatcher(input, deadline, records.queryOperations());
        return new SessionScopeExecution.Matcher() {
          public void accept(
              SessionScopeExecution.Value value, String digest, SessionScopeExecution.Cut cut) {
            matcher.accept((JsonNode) ((Value) value).raw(), digest, ((Cut) cut).raw());
          }

          public String output(String id, String digest) {
            return matcher.output(id, digest);
          }
        };
      }

      public SessionScopeExecution.Budget budget() {
        return records.priorBudget(deadline);
      }

      public void deadline() {
        deadline.run();
      }

      public String caller() {
        return s(caller, "id");
      }

      public void validate(String output) {
        records.scopes.contracts.output(output);
      }

      public String scanDigest(List<SessionScopeExecution.Ref> refs) {
        return Payloads.hash(refsJson(refs));
      }
    };
  }

  SessionScopeExecution.ScopeRead read(String normalized) {
    return new SessionScopeExecution.ScopeRead() {
      public SessionScopeExecution.Scope require() {
        return new Scope(records.scopes.require(caller));
      }

      public SessionScopeExecution.Normalized normalized() {
        var value = records.contracts.json(normalized, 4096);
        return new SessionScopeExecution.Normalized() {
          public String target() {
            return value.path("target_session_id").asText();
          }

          public SessionEventPort.Input input(String id) {
            return new SessionEventPort.Input(
                value.path("seq").asLong(),
                id,
                value.path("before").asInt(),
                value.path("after").asInt());
          }

          public long upper() {
            return value.path("upper_seq").asLong();
          }
        };
      }

      public String callerId() {
        return s(caller, "id");
      }

      public SessionScopeExecution.Source caller() {
        return new Source(caller);
      }

      public SessionScopeExecution.Budget budget() {
        return records.priorBudget(deadline);
      }
    };
  }

  SessionScopeExecution.Window window(
      Map<String, Object> task, SessionEventPort.Input input, long upper, String boundary) {
    return new SessionScopeExecution.Window() {
      public void history() {
        records.history(task, true);
      }

      public SessionEventPort.Input input() {
        return input;
      }

      public String session() {
        return s(task, "id");
      }

      public long upper() {
        return upper;
      }

      public List<SessionScopeExecution.Event> events(long from, long to) {
        return records.windowRows(task, from, to).stream()
            .<SessionScopeExecution.Event>map(r -> new Event(task, r))
            .toList();
      }

      public SessionScopeExecution.Ref ref(SessionScopeExecution.Event event) {
        return JdbcSessionScopes.task(task)
            ? scopeRef(s(task, "id"), event.seq(), event.digest())
            : new Ref(Map.of("seq", event.seq(), "event_sha256", event.digest()));
      }

      public SessionScopeExecution.Value summary(SessionScopeExecution.Value value) {
        return new Value(JdbcSessionRecords.readSummary((JsonNode) ((Value) value).raw()));
      }

      public SessionScopeExecution.ReadResult output(
          long from,
          long to,
          SessionScopeExecution.Value target,
          String targetDigest,
          List<SessionScopeExecution.Value> before,
          List<SessionScopeExecution.Value> after,
          List<SessionScopeExecution.Ref> refs,
          List<SessionScopeExecution.Event> events) {
        var value = new LinkedHashMap<String, Object>();
        value.put("version", 1);
        value.put("session_id", s(task, "id"));
        value.put("history_version", records.version(task));
        value.put("content_policy", "public-v1");
        value.put("boundary", boundary);
        value.put("upper_seq", upper);
        value.put(
            "window",
            Map.of(
                "from_seq",
                from,
                "to_seq",
                to,
                "before_requested",
                input.before(),
                "after_requested",
                input.after()));
        value.put("target", ((Value) target).raw());
        value.put("before", before.stream().map(v -> ((Value) v).raw()).toList());
        value.put("after", after.stream().map(v -> ((Value) v).raw()).toList());
        String raw = Payloads.json(value);
        records.contract(task).output(raw);
        return new SessionScopeExecution.ReadResult(
            raw,
            from,
            to,
            targetDigest,
            refsJson(refs),
            events.stream().mapToInt(e -> Math.toIntExact(e.storedBytes())).sum());
      }
    };
  }

  private static Ref scopeRef(String id, long seq, String digest) {
    var value = new LinkedHashMap<String, Object>();
    value.put("session_id", id);
    value.put("seq", seq);
    value.put("event_sha256", digest);
    return new Ref(value);
  }

  private static String refsJson(List<SessionScopeExecution.Ref> refs) {
    return Payloads.json(refs.stream().map(r -> ((Ref) r).raw()).toList());
  }
}
