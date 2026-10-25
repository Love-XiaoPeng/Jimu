package io.github.lovexiaopeng.jimu.kernel.execution.port;

/** Shared directory, model, invocation, session and todo commit persistence. */
@FunctionalInterface
public interface CommitFence {
  int update();
}
