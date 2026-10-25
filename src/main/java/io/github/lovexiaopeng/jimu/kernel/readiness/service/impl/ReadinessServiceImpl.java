package io.github.lovexiaopeng.jimu.kernel.readiness.service.impl;

import io.github.lovexiaopeng.jimu.kernel.plugin.api.PluginCatalog;
import io.github.lovexiaopeng.jimu.kernel.readiness.api.Readiness;
import io.github.lovexiaopeng.jimu.kernel.readiness.port.ResourceProbe;

/** 根据发现集与必需资源探测结果判断就绪；空发现集不会触发资源探测。 */
public final class ReadinessServiceImpl implements Readiness {
  private final String role;
  private final PluginCatalog catalog;
  private final ResourceProbe probe;

  public ReadinessServiceImpl(String role, PluginCatalog catalog, ResourceProbe probe) {
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
