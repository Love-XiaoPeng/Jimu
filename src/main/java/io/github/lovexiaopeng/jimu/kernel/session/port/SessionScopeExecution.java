package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.plugin.api.SessionEventPort;
import java.util.List;

/** scope扫描、窗口和原JSON投影的窄边界；行/正文保持惰性，不提前证明。 */
public interface SessionScopeExecution {
  interface Value {}

  interface Ref {}

  interface Budget extends AutoCloseable {
    void close();
  }

  record SearchResult(String output, String scanDigest, int rows, int eventBytes) {}

  record ReadResult(
      String output, long from, long to, String targetDigest, String sources, int eventBytes) {}

  interface Event {
    long seq();

    String digest();

    long storedBytes();

    int snapshotBytes();

    Value verify();
  }

  interface Cut {
    long upper();
  }

  interface Source {
    List<Event> scan(long upper);

    Ref ref(long seq, String digest);

    Window window(SessionEventPort.Input input, long upper, String boundary);
  }

  interface Scope {
    boolean contains(String id);

    int size();

    Source source(int index);

    Cut cut(int index);

    Source find(String id);

    String digest();
  }

  interface Matcher {
    void accept(Value value, String digest, Cut cut);

    String output(String caller, String scopeDigest);
  }

  interface Prior {
    Scope require();

    List<String> sessions();

    Matcher matcher();

    Budget budget();

    void deadline();

    String caller();

    void validate(String output);

    String scanDigest(List<Ref> refs);
  }

  interface Normalized {
    String target();

    SessionEventPort.Input input(String target);

    long upper();
  }

  interface ScopeRead {
    Scope require();

    Normalized normalized();

    String callerId();

    Source caller();

    Budget budget();
  }

  interface Window {
    void history();

    SessionEventPort.Input input();

    String session();

    long upper();

    List<Event> events(long from, long to);

    Ref ref(Event event);

    Value summary(Value value);

    ReadResult output(
        long from,
        long to,
        Value target,
        String targetDigest,
        List<Value> before,
        List<Value> after,
        List<Ref> refs,
        List<Event> events);
  }
}
