package io.github.lovexiaopeng.jimu.kernel.readiness.application;

import io.github.lovexiaopeng.jimu.kernel.plugin.api.PluginCatalog;
import io.github.lovexiaopeng.jimu.kernel.readiness.api.Readiness;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;

public final class ReadinessService implements Readiness {
  private final String role;
  private final PluginCatalog catalog;
  private final ResourceProbe probe;

  public ReadinessService(String role, PluginCatalog catalog, ResourceProbe probe) {
    this.role = role;
    this.catalog = catalog;
    this.probe = probe;
  }

  @Override
  public Snapshot snapshot() {
    int count = catalog.descriptors().size();
    return new Snapshot(role, count > 0 && probe.ready() ? "UP" : "DOWN", count);
  }
}
