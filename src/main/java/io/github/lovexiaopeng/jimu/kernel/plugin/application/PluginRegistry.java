package io.github.lovexiaopeng.jimu.kernel.plugin.application;

import io.github.lovexiaopeng.jimu.kernel.plugin.api.PluginCatalog;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.HashSet;
import java.util.List;

/** Immutable discovery set. Different versions belong in distinct task discovery sets. */
public final class PluginRegistry implements PluginCatalog {
  private final List<ToolDescriptor> descriptors;

  public PluginRegistry(List<ToolDescriptor> descriptors) {
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
