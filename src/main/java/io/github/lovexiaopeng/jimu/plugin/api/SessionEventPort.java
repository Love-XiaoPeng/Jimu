package io.github.lovexiaopeng.jimu.plugin.api;

/** Read-only port bound by the host to one immutable caller, step and response slot. */
public interface SessionEventPort {
  record Input(long seq, String sessionId, int before, int after) {}

  /** Returns the complete versioned public-event result, never internal recovery snapshots. */
  String read(Input input);
}
