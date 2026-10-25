package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionResponsePreflight;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import java.util.List;
import java.util.Map;

/** 复用原 codec、metadata、输入和投影端口；不增加查询或事务。 */
final class StoredSessionResponsePreflight implements SessionResponsePreflight {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;

  StoredSessionResponsePreflight(JdbcSessionRecords records, Map<String, Object> task) {
    this.records = records;
    this.task = task;
  }

  public void metadata(long additional) {
    records.metadata(task, false, additional);
  }

  public int bytes(String value) {
    return SessionContracts.bytes(value);
  }

  public Response decode(String value) {
    var parsed = Payloads.read(value, DurableAgent.Response.class);
    return new Response() {
      public int version() {
        return parsed.version();
      }

      public int callCount() {
        return parsed.calls().size();
      }

      public WireCall call(int index) {
        var wire = parsed.calls().get(index);
        return new WireCall() {
          public String id() {
            return wire.id();
          }

          public String name() {
            return wire.name();
          }

          public String arguments() {
            return wire.arguments();
          }

          public String type() {
            return wire.type();
          }
        };
      }

      public String content() {
        return parsed.content();
      }

      public Projection project() {
        var projected = records.projectedCalls(task, parsed.calls());
        return new Projection() {
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

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public boolean searchTask() {
    return JdbcSessionRecords.searchTask(task);
  }

  public boolean traceTask() {
    return JdbcSessionRecords.traceTask(task);
  }

  public void callId(String id) {
    records.contracts.callId(id, records.sensitive());
  }

  public void validateInput(Call call) {
    records.validateInput(task, call);
  }

  public Text publicText(String content) {
    var text = records.contracts.publicText(content, records.sensitive());
    return new Text(text.content(), text.redacted());
  }

  public int version() {
    return records.version(task);
  }

  public String sessionId() {
    return JdbcSessionRecords.s(task, "id");
  }

  public Map<String, Object> source(String kind, String step, String invocation) {
    return records.source(kind, step, invocation);
  }

  public void event(Map<String, Object> preview) {
    records.contract(task).event(Payloads.json(preview));
  }
}
