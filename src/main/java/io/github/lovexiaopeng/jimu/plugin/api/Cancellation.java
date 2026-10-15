package io.github.lovexiaopeng.jimu.plugin.api;

@FunctionalInterface
public interface Cancellation {
  void throwIfCancelled();
}
