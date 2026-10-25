package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import java.util.List;
import java.util.Map;

/** 原响应 codec、资格和冻结事件的惰性边界；沿调用者工作单元执行。 */
public interface SessionResponsePreflight {
  interface WireCall {
    String id();

    String name();

    String arguments();

    String type();
  }

  interface Response {
    int version();

    int callCount();

    WireCall call(int index);

    String content();

    Projection project();
  }

  interface Projection {
    boolean redacted();

    List<?> calls();
  }

  record Text(String content, boolean redacted) {}

  void metadata(long additional);

  int bytes(String value);

  Response decode(String value);

  boolean scopeTask();

  boolean searchTask();

  boolean traceTask();

  void callId(String id);

  void validateInput(Call call);

  Text publicText(String content);

  int version();

  String sessionId();

  Map<String, Object> source(String kind, String step, String invocation);

  void event(Map<String, Object> preview);
}
