package io.github.lovexiaopeng.jimu.kernel.directory.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** Original catalog values, read lazily in the caller's existing unit of work. */
public interface DirectoryAvailability {
  boolean enabled();

  ModelSnapshot selected();

  void requireDirectory(ModelSnapshot model);

  List<Installation> installed();

  ToolDescriptor descriptor();

  interface Installation {
    String version();

    String digest();
  }
}
