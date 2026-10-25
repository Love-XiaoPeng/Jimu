package io.github.lovexiaopeng.jimu.kernel.session.port;

/** Event append under the caller's existing locks and unit of work; values stay lazy. */
public interface SessionEventAppend {
  interface History {
    long next();
  }

  interface Event {
    int bytes();
  }

  boolean terminal();

  History history(boolean readable);

  Long prior();

  Event event(long seq);

  void validate(Event event);

  void body(int bytes);

  void insert(long seq, Event event);

  void advance();
}
