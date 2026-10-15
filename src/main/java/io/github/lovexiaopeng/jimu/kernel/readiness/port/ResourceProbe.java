package io.github.lovexiaopeng.jimu.kernel.readiness.port;

@FunctionalInterface
public interface ResourceProbe {
  boolean ready();
}
