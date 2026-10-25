package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Reliable event recursion and original row/codec access, lazy in the caller's UoW. */
public interface SessionFacts {
  interface Append {
    void changed(String fact);

    long reliable(String fact);

    long append(long reliable);

    boolean terminal();

    void account(boolean closing);
  }

  record Text(String content, boolean redacted) {}

  interface Projection {
    boolean redacted();

    List<?> calls();
  }

  interface Response {
    Text text();

    Projection project();
  }

  interface Step {
    String state();

    Response response();

    long ordinal();

    String requestDigest();

    String responseDigest();
  }

  interface Attempt {
    String id();

    long fence();
  }

  interface Terminal {
    Object state();

    Object code();

    String digest();
  }

  interface Fact {
    boolean session();

    Text goalText();

    String requestDigest();

    Step step(String id);

    Attempt attempt();

    String tenant();

    String owner();

    String taskId();

    String worker();

    Instant deadline();

    String goal();

    List<String> caps();

    long installationEpoch();

    Terminal terminal();

    Map<String, Object> source(String kind, String step, String invocation);

    void append(
        String fact,
        String type,
        Map<String, Object> source,
        Map<String, Object> data,
        boolean redacted,
        String digest,
        Lease lease);
  }
}
