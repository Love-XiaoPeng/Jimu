package io.github.lovexiaopeng.jimu.kernel.plugin.service.impl;

import io.github.lovexiaopeng.jimu.kernel.plugin.api.PluginCatalog;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.HashSet;
import java.util.List;

/** 保留发现顺序的不可变目录；拒绝重复标识和能力，不同版本应属于不同任务发现集。 */
public final class PluginCatalogServiceImpl implements PluginCatalog {
  private final List<ToolDescriptor> descriptors;

  public PluginCatalogServiceImpl(List<ToolDescriptor> descriptors) {
    var identities = new HashSet<String>();
    var capabilities = new HashSet<String>();
    for (ToolDescriptor descriptor : descriptors) {
      if (!identities.add(descriptor.pluginId() + "@" + descriptor.version())) {
        throw new IllegalArgumentException("Duplicate plugin identity");
      }
      if (!capabilities.add(descriptor.capability())) {
        throw new IllegalArgumentException("Duplicate capability in discovery set");
      }
    }
    this.descriptors = List.copyOf(descriptors);
  }

  @Override
  public List<ToolDescriptor> descriptors() {
    return descriptors;
  }
}
