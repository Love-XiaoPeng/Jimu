package io.github.lovexiaopeng.jimu.kernel.plugin.api;

import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

public interface PluginCatalog {
  List<ToolDescriptor> descriptors();
}
