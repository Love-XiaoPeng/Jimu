package io.github.lovexiaopeng.jimu.kernel.readiness.api;

public interface Readiness {
  Snapshot snapshot();

  record Snapshot(String role, String status, int plugins) {}
}
