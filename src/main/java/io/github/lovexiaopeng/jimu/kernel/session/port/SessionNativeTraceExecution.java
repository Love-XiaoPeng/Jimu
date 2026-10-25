package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** 原生trace执行的惰性类型边界；JSON/原字节和真实存储留在适配器。 */
public interface SessionNativeTraceExecution {
  record Result(
      String output,
      String scanDigest,
      String relationsDigest,
      String targetDigest,
      int rows,
      int eventBytes,
      int edges) {}

  record Scan(int seq, String digest) {
    public Scan {
      java.util.Objects.requireNonNull(digest);
    }
  }

  record Relation(int seq, String digest) {
    public Relation {
      java.util.Objects.requireNonNull(digest);
    }
  }

  record Receipt(String digest, String outcome) {}

  interface History {
    long next();
  }

  interface Source {
    long seq();
  }

  interface Value {
    Iterable<Source> sources();

    int sourceCount();
  }

  interface Summary {
    int bytes();
  }

  interface Event {
    long seq();

    int snapshotBytes();

    Value verify();

    String digest();

    boolean read();

    Receipt receipt();

    int characters();
  }

  interface Budget extends AutoCloseable {
    void operation(long amount);

    @Override
    void close();
  }

  History history();

  boolean enabled();

  String requestedSession();

  String session();

  long seq();

  long upper();

  boolean exists();

  Budget budget();

  List<Event> scan();

  void deadline();

  Summary summary(Value value, String digest);

  String output(Summary summary, Value target, List<Long> derived);

  void validate(String raw);

  String scanDigest(List<Scan> scan);

  String relationsDigest(List<Relation> relations);
}
