package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.session.SessionContracts;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCallProjections;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 原codec/公开文本/ToolCall装配；逐项惰性读取，既有调用者负责事务和持久化。 */
final class StoredSessionCallProjections implements SessionCallProjections {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;
  private final List<DurableAgent.ToolCall> original;
  private final List<DurableAgent.ToolCall> projected = new ArrayList<>();
  private JdbcSessionRecords.PublicCalls result;

  StoredSessionCallProjections(
      JdbcSessionRecords records, Map<String, Object> task, List<DurableAgent.ToolCall> original) {
    this.records = records;
    this.task = task;
    this.original = original;
  }

  public boolean searchTask() {
    return JdbcSessionRecords.searchTask(task);
  }

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public int bytes(String value) {
    return SessionContracts.bytes(value);
  }

  public Iterable<SessionCallProjections.Call> calls() {
    return () ->
        new Iterator<>() {
          private final Iterator<DurableAgent.ToolCall> source = original.iterator();

          public boolean hasNext() {
            return source.hasNext();
          }

          public SessionCallProjections.Call next() {
            return new Entry(source.next());
          }
        };
  }

  public void complete(boolean redacted) {
    result = new JdbcSessionRecords.PublicCalls(List.copyOf(projected), redacted);
  }

  JdbcSessionRecords.PublicCalls result() {
    return result;
  }

  private final class Entry implements SessionCallProjections.Call {
    private final DurableAgent.ToolCall call;
    private ObjectNode node;

    Entry(DurableAgent.ToolCall call) {
      this.call = call;
    }

    public String arguments() {
      return call.arguments();
    }

    public String name() {
      return call.name();
    }

    public void parseObject(String arguments, int maxBytes) {
      node = (ObjectNode) records.contracts.strictJson(arguments, maxBytes);
    }

    public Text publicQuery() {
      var text = records.contracts.publicText(node.path("query").textValue(), records.sensitive());
      return new Text(text.content(), text.redacted());
    }

    public String replaceQuery(String content) {
      node.put("query", content);
      return Payloads.json(node);
    }

    public void append(String arguments) {
      projected.add(new DurableAgent.ToolCall(call.id(), call.type(), call.name(), arguments));
    }
  }
}
