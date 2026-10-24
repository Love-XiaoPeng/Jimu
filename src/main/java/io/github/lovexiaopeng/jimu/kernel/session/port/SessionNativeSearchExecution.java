package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.List;

/** 原生检索的惰性执行边界，持久化、codec和原matcher由适配器提供。 */
public interface SessionNativeSearchExecution {
  record Result(String output, String scanDigest, int count, int eventBytes) {}

  record Scan(int seq, String digest) {}

  interface Value {}

  interface Event {
    long seq();

    Value verify();

    int snapshotBytes();

    String digest();
  }

  interface Matcher {
    void accept(Value value, String digest, long upper);

    String output();
  }

  interface Budget extends AutoCloseable {
    @Override
    void close();
  }

  interface History {
    long next();
  }

  History history();

  boolean enabled();

  void normalized();

  Matcher matcher();

  Budget budget();

  Long from();

  Long to();

  long upper();

  List<Event> scan();

  void deadline();

  void validate(String output);

  String scanDigest(List<Scan> scanned);
}
