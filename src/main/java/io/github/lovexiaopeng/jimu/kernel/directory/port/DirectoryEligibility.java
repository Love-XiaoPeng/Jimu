package io.github.lovexiaopeng.jimu.kernel.directory.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** Original directory qualification values, evaluated only at the requesting call. */
public interface DirectoryEligibility {
  List<String> capabilities();

  PluginPin pin(String name);

  Installation installation(String name);

  ToolDescriptor directoryDescriptor();

  ToolDescriptor fileDescriptor(String name);

  void projection();

  interface Installation {
    String version();

    String digest();

    long epoch();
  }
}
