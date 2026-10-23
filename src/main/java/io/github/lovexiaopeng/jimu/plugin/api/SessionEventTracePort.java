package io.github.lovexiaopeng.jimu.plugin.api;

/** Direct captured read sources in the immutable caller's authorized session prefix. */
@FunctionalInterface
public interface SessionEventTracePort {
  record Input(long seq, String sessionId) {}

  String trace(Input input);
}
